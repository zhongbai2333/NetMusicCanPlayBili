package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.media.audio.PcmPlanarConverter;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

public final class OpenALTappedAudioInputStream extends AudioInputStream {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SKIP_BUFFER_SIZE = 64 * 1024;
    private static final AtomicLong INSTANCES_CREATED = new AtomicLong();
    private static final AtomicLong CLOSES_COMPLETED = new AtomicLong();

    private final AudioInputStream source;
    private final StereoOpenALHandler stereo;
    private final Runnable onClose;
    private final int frameBytes;
    private final byte[] carry;
    private final byte[] skipBuffer = new byte[SKIP_BUFFER_SIZE];
    private byte[] tapBuffer = new byte[0];
    private final long initialSkipBytes;
    private long skipBytesRemaining;
    private int carryLength;
    private volatile boolean closed;
    private boolean firstDiagnostics;
    private boolean inputFinished;
    private boolean skipLogged;

    public OpenALTappedAudioInputStream(AudioInputStream source, StereoOpenALHandler stereo, Runnable onClose) {
        this(source, stereo, onClose, 0f);
    }

    public OpenALTappedAudioInputStream(
            AudioInputStream source,
            StereoOpenALHandler stereo,
            Runnable onClose,
            float startOffsetSeconds) {
        super(source, source.getFormat(), AudioSystem.NOT_SPECIFIED);
        this.source = source;
        this.stereo = stereo;
        this.onClose = onClose;
        AudioFormat format = source.getFormat();
        int bytesPerSample = Math.max(1, (format.getSampleSizeInBits() + 7) / 8);
        this.frameBytes = Math.max(1, bytesPerSample * Math.max(1, format.getChannels()));
        this.skipBytesRemaining = Math.max(0L, Math.round(format.getSampleRate() * Math.max(0f, startOffsetSeconds))
                * (long) this.frameBytes);
        this.initialSkipBytes = this.skipBytesRemaining;
        this.carry = new byte[this.frameBytes];
        INSTANCES_CREATED.incrementAndGet();
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (closed) {
            return -1;
        }
        if (!drainStartOffset()) {
            return -1;
        }
        int n = source.read(b, off, len);
        if (n <= 0) {
            if (n < 0) {
                finishInput();
            }
            return n;
        }

        tap(b, off, n);
        Arrays.fill(b, off, off + n, (byte) 0);
        return n;
    }

    /**
     * 真正丢弃起始偏移的 PCM，而不是把偏移段作为静音返回给 Minecraft。
     * 否则 seek 到 2 分钟处会先真实播放 2 分钟静音，OpenAL 一直拿不到后续音频。
     */
    private boolean drainStartOffset() throws IOException {
        while (skipBytesRemaining > 0L && !closed) {
            int request = (int) Math.min(skipBuffer.length, skipBytesRemaining);
            int n = source.read(skipBuffer, 0, request);
            if (n < 0) {
                skipBytesRemaining = 0L;
                finishInput();
                return false;
            }
            if (n == 0) {
                continue;
            }
            skipBytesRemaining -= n;
        }
        if (!skipLogged && initialSkipBytes > 0L) {
            skipLogged = true;
            LOGGER.debug("OpenAL tapped stream skipped {} PCM bytes for start offset", initialSkipBytes);
        }
        return !closed;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException error = null;
        try {
            try {
                source.close();
            } catch (IOException e) {
                error = e;
            }
            try {
                onClose.run();
            } catch (RuntimeException e) {
                if (error == null) {
                    error = new IOException("OpenAL tapped stream cleanup failed", e);
                } else {
                    error.addSuppressed(e);
                }
            }
        } finally {
            CLOSES_COMPLETED.incrementAndGet();
        }
        if (error != null) {
            throw error;
        }
    }

    private void tap(byte[] pcm, int offset, int length) {
        if (stereo == null || length <= 0) {
            return;
        }
        int combinedLength = carryLength + length;
        ensureTapCapacity(combinedLength);
        if (carryLength > 0) {
            System.arraycopy(carry, 0, tapBuffer, 0, carryLength);
        }
        System.arraycopy(pcm, offset, tapBuffer, carryLength, length);

        int aligned = combinedLength - (combinedLength % frameBytes);
        if (aligned > 0) {
            float[][] planar = PcmPlanarConverter.convert(tapBuffer, 0, aligned, getFormat());
            StereoOpenALHandler.PcmQuality quality = stereo.observeFirstPcm(planar);
            if (!firstDiagnostics && quality.samples() >= 1_024L) {
                firstDiagnostics = true;
                logFirstPcmDiagnostics(aligned, quality);
            }
            stereo.enqueuePcm(planar);
        }

        int remain = combinedLength - aligned;
        if (remain > 0) {
            System.arraycopy(tapBuffer, aligned, carry, 0, remain);
        }
        carryLength = remain;
    }

    private void ensureTapCapacity(int required) {
        if (tapBuffer.length >= required) {
            return;
        }
        int capacity = Math.max(4096, tapBuffer.length);
        while (capacity < required && capacity <= Integer.MAX_VALUE / 2) {
            capacity *= 2;
        }
        if (capacity < required) {
            capacity = required;
        }
        tapBuffer = new byte[capacity];
    }

    private void finishInput() {
        if (!inputFinished && stereo != null) {
            inputFinished = true;
            stereo.finishInput();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /** Global scalar lifecycle state used to verify the Minecraft streaming-channel close path. */
    public static LifecycleSnapshot lifecycleSnapshot() {
        long created = INSTANCES_CREATED.get();
        long closed = CLOSES_COMPLETED.get();
        return new LifecycleSnapshot(created, closed, Math.max(0L, created - closed));
    }

    public record LifecycleSnapshot(long instancesCreated, long closesCompleted, long activeInstances) {
    }

    private void logFirstPcmDiagnostics(int bytes, StereoOpenALHandler.PcmQuality quality) {
        AudioFormat format = getFormat();
        LOGGER.debug(
                "PCM tap first chunk: bytes={} frameBytes={} aligned={} sampleSize={} endian={} samples={} peak={} rms={} clippedRatio={}",
                bytes, frameBytes, bytes % frameBytes == 0, format.getSampleSizeInBits(),
                format.isBigEndian() ? "big" : "little", quality.samples(), quality.peak(), quality.rms(),
                quality.clippedRatio());
    }
}
