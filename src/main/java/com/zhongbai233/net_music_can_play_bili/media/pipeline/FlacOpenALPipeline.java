package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.github.tartaricacid.netmusic.soundlibs.org.jflac.sound.spi.Flac2PcmAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
import net.minecraft.core.BlockPos;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FlacOpenALPipeline extends AbstractAudioPipeline {
    private static final int PIPE_BUFFER_SIZE = 4 * 1024 * 1024;

    private final AudioFormat format;
    private final BlockingAudioPipe compressedPipe = new BlockingAudioPipe(PIPE_BUFFER_SIZE);
    private final AtomicBoolean ownerClosed;
    private final AtomicBoolean cleaned = new AtomicBoolean(false);
    private final StereoOpenALHandler stereo;
    private final float startOffsetSeconds;
    private long compressedBytes;

    public FlacOpenALPipeline(byte[] dfLa, AtomicBoolean ownerClosed) throws IOException {
        this(dfLa, ownerClosed, null);
    }

    public FlacOpenALPipeline(byte[] dfLa, AtomicBoolean ownerClosed, BlockPos sourcePos) throws IOException {
        this(dfLa, ownerClosed, sourcePos, 0f);
    }

    public FlacOpenALPipeline(byte[] dfLa, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds)
            throws IOException {
        this(dfLa, ownerClosed, sourcePos, startOffsetSeconds, startOffsetSeconds);
    }

    public FlacOpenALPipeline(byte[] dfLa, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds,
            float timelineStartOffsetSeconds)
            throws IOException {
        this(dfLa, ownerClosed, sourcePos, startOffsetSeconds, timelineStartOffsetSeconds, "");
    }

    public FlacOpenALPipeline(byte[] dfLa, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds,
            float timelineStartOffsetSeconds, String sessionId)
            throws IOException {
        super("fMP4", "flac", null, true);
        this.format = FlacStreamSupport.audioFormat(dfLa);
        this.ownerClosed = ownerClosed;
        this.stereo = new StereoOpenALHandler();
        this.startOffsetSeconds = Math.max(0f, startOffsetSeconds);
        this.stereo.setSampleRate((int) format.getSampleRate());
        DolbyAudioRegistry.registerStereo(stereo, sourcePos, timelineStartOffsetSeconds, sessionId);
        FlacStreamSupport.writeNativeHeader(compressedPipe, dfLa);
    }

    @Override
    public AudioFormat format() {
        return format;
    }

    @Override
    public String detail() {
        return format.getSampleSizeInBits() > 16 ? "Hi-Res FLAC" : "FLAC";
    }

    @Override
    public long onMdat(InputStream input, long length) throws IOException {
        long copied = FlacStreamSupport.copyMdat(input, length, compressedPipe, ownerClosed::get);
        compressedBytes += copied;
        return copied;
    }

    @Override
    public String statsSummary() {
        return "flacCompressedBytes=" + compressedBytes;
    }

    @Override
    public void finish() {
        compressedPipe.closeWriter();
    }

    @Override
    public void close() {
        if (cleaned.compareAndSet(false, true)) {
            compressedPipe.closeWriter();
            compressedPipe.close();
            DolbyAudioRegistry.unregisterStereo(stereo);
            stereo.cleanup();
        }
    }

    public AudioInputStream openTappedStream() {
        AudioInputStream decoded = new Flac2PcmAudioInputStream(compressedPipe, format, AudioSystem.NOT_SPECIFIED);
        return new OpenALTappedAudioInputStream(decoded, stereo, this::close, startOffsetSeconds);
    }

}
