package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.media.audio.PcmPlanarConverter;
import net.minecraft.core.BlockPos;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

public final class AacOpenALPipeline extends AbstractAudioPipeline {
    private final AacFrameDecoder decoder;
    private final AtomicBoolean cleaned = new AtomicBoolean(false);
    private final StereoOpenALHandler stereo;
    private long skipBytesRemaining;

    public AacOpenALPipeline(byte[] asc, AtomicBoolean ownerClosed) {
        this(asc, ownerClosed, null);
    }

    public AacOpenALPipeline(byte[] asc, AtomicBoolean ownerClosed, BlockPos sourcePos) {
        this(asc, ownerClosed, sourcePos, 0f);
    }

    public AacOpenALPipeline(byte[] asc, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds) {
        this(asc, ownerClosed, sourcePos, startOffsetSeconds, startOffsetSeconds);
    }

    public AacOpenALPipeline(byte[] asc, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds,
            float timelineStartOffsetSeconds) {
        this(asc, ownerClosed, sourcePos, startOffsetSeconds, timelineStartOffsetSeconds, "");
    }

    public AacOpenALPipeline(byte[] asc, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds,
            float timelineStartOffsetSeconds, String sessionId) {
        this(asc, ownerClosed, sourcePos, startOffsetSeconds, timelineStartOffsetSeconds, sessionId, null);
    }

    public AacOpenALPipeline(byte[] asc, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds,
            float timelineStartOffsetSeconds, String sessionId, UUID ownerId) {
        super("fMP4", "aac", null, true);
        this.decoder = new AacFrameDecoder(asc, ownerClosed::get);
        this.stereo = new StereoOpenALHandler();
        this.stereo.setSampleRate((int) decoder.format().getSampleRate());
        this.skipBytesRemaining = skipBytes(decoder.format(), startOffsetSeconds);
        ClientAudioOutputRegistry.registerStereo(stereo, sourcePos, timelineStartOffsetSeconds, sessionId, ownerId);
    }

    @Override
    public AudioFormat format() {
        return decoder.format();
    }

    @Override
    public String detail() {
        return decoder.format().isBigEndian() ? "big-endian PCM" : "little-endian PCM";
    }

    @Override
    public void onMoof(int[] sampleSizes) {
        decoder.onMoof(sampleSizes);
    }

    @Override
    public long onMdat(InputStream input, long length) throws IOException {
        return decoder.onMdat(input, length, this::enqueuePcm);
    }

    @Override
    public long decodedFrames() {
        return decoder.decodedFrames();
    }

    @Override
    public void finish() {
        stereo.finishInput();
    }

    @Override
    public void close() {
        if (cleaned.compareAndSet(false, true)) {
            ClientAudioOutputRegistry.unregisterStereo(stereo);
            stereo.cleanup();
        }
    }

    private void enqueuePcm(byte[] pcm, AudioFormat format) {
        byte[] audible = applySkip(pcm);
        if (audible.length > 0) {
            stereo.enqueuePcm(PcmPlanarConverter.from16Bit(audible, format.getChannels()));
        }
    }

    private byte[] applySkip(byte[] pcm) {
        if (skipBytesRemaining <= 0L || pcm.length == 0) {
            return pcm;
        }
        int skipped = (int) Math.min(skipBytesRemaining, pcm.length);
        skipBytesRemaining -= skipped;
        if (skipped >= pcm.length) {
            return new byte[0];
        }
        byte[] tail = new byte[pcm.length - skipped];
        System.arraycopy(pcm, skipped, tail, 0, tail.length);
        return tail;
    }

    private static long skipBytes(AudioFormat format, float seconds) {
        if (seconds <= 0f) {
            return 0L;
        }
        int frameSize = format.getFrameSize();
        if (frameSize <= 0) {
            int bytesPerSample = Math.max(1, (format.getSampleSizeInBits() + 7) / 8);
            frameSize = bytesPerSample * Math.max(1, format.getChannels());
        }
        return Math.max(0L, Math.round(format.getSampleRate() * seconds) * (long) frameSize);
    }
}
