package com.zhongbai233.net_music_can_play_bili.bili.pipeline;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.util.Arrays;

public final class OpenALTappedAudioInputStream extends AudioInputStream {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DIAGNOSTIC_MIN_FRAMES = 256;

    private final AudioInputStream source;
    private final StereoOpenALHandler stereo;
    private final Runnable onClose;
    private final int frameBytes;
    private final byte[] carry;
    private int carryLength;
    private boolean closed;
    private boolean firstDiagnostics;
    private boolean inputFinished;

    public OpenALTappedAudioInputStream(AudioInputStream source, StereoOpenALHandler stereo, Runnable onClose) {
        super(source, source.getFormat(), AudioSystem.NOT_SPECIFIED);
        this.source = source;
        this.stereo = stereo;
        this.onClose = onClose;
        AudioFormat format = source.getFormat();
        int bytesPerSample = Math.max(1, (format.getSampleSizeInBits() + 7) / 8);
        this.frameBytes = Math.max(1, bytesPerSample * Math.max(1, format.getChannels()));
        this.carry = new byte[this.frameBytes];
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
        int n = source.read(b, off, len);
        if (n <= 0) {
            if (n < 0) {
                finishInput();
            }
            return n;
        }

        byte[] realPcm = new byte[n];
        System.arraycopy(b, off, realPcm, 0, n);
        tap(realPcm, n);
        Arrays.fill(b, off, off + n, (byte) 0);
        return n;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException error = null;
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
        if (error != null) {
            throw error;
        }
    }

    private void tap(byte[] pcm, int length) {
        if (stereo == null || length <= 0) {
            return;
        }
        byte[] combined = new byte[carryLength + length];
        if (carryLength > 0) {
            System.arraycopy(carry, 0, combined, 0, carryLength);
        }
        System.arraycopy(pcm, 0, combined, carryLength, length);

        int aligned = combined.length - (combined.length % frameBytes);
        if (aligned > 0) {
            byte[] alignedPcm = new byte[aligned];
            System.arraycopy(combined, 0, alignedPcm, 0, aligned);
            float[][] planar = StereoOpenALHandler.pcmToFloatPlanar(alignedPcm, getFormat());
            if (!firstDiagnostics && alignedPcm.length >= frameBytes * DIAGNOSTIC_MIN_FRAMES) {
                firstDiagnostics = true;
                logFirstPcmDiagnostics(alignedPcm.length, planar);
            }
            stereo.enqueuePcm(planar);
        }

        int remain = combined.length - aligned;
        if (remain > 0) {
            System.arraycopy(combined, aligned, carry, 0, remain);
        }
        carryLength = remain;
    }

    private void finishInput() {
        if (!inputFinished && stereo != null) {
            inputFinished = true;
            stereo.finishInput();
        }
    }

    private void logFirstPcmDiagnostics(int bytes, float[][] planar) {
        AudioFormat format = getFormat();
        int samples = planar.length > 0 && planar[0] != null ? planar[0].length : 0;
        double sumSquares = 0.0;
        float peak = 0.0f;
        int count = 0;
        for (float[] channel : planar) {
            if (channel == null) {
                continue;
            }
            for (float sample : channel) {
                float abs = Math.abs(sample);
                if (abs > peak) {
                    peak = abs;
                }
                sumSquares += sample * sample;
                count++;
            }
        }
        double rms = count > 0 ? Math.sqrt(sumSquares / count) : 0.0;
        LOGGER.debug(
                "FLAC PCM tap first chunk: bytes={} frameBytes={} sampleSize={} endian={} samples={} peak={} rms={}",
                bytes, frameBytes, format.getSampleSizeInBits(),
                format.isBigEndian() ? "big" : "little", samples, peak, rms);
    }
}
