package com.zhongbai233.net_music_can_play_bili.bili.pipeline;

import com.github.tartaricacid.netmusic.soundlibs.org.jflac.sound.spi.Flac2PcmAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.bili.stream.BlockingAudioPipe;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FlacOpenALPipeline implements AudioDecodePipeline {
    private static final int PIPE_BUFFER_SIZE = 4 * 1024 * 1024;

    private final AudioFormat format;
    private final BlockingAudioPipe compressedPipe = new BlockingAudioPipe(PIPE_BUFFER_SIZE);
    private final AtomicBoolean ownerClosed;
    private final AtomicBoolean cleaned = new AtomicBoolean(false);
    private final StereoOpenALHandler stereo;
    private long compressedBytes;

    public FlacOpenALPipeline(byte[] dfLa, AtomicBoolean ownerClosed) throws IOException {
        this.format = FlacStreamSupport.audioFormat(dfLa);
        this.ownerClosed = ownerClosed;
        this.stereo = new StereoOpenALHandler();
        this.stereo.setSampleRate((int) format.getSampleRate());
        DolbyAudioRegistry.registerStereo(stereo);
        FlacStreamSupport.writeNativeHeader(compressedPipe, dfLa);
    }

    @Override
    public AudioFormat format() {
        return format;
    }

    @Override
    public String container() {
        return "fMP4";
    }

    @Override
    public String codec() {
        return "flac";
    }

    @Override
    public String detail() {
        return format.getSampleSizeInBits() > 16 ? "Hi-Res FLAC" : "FLAC";
    }

    @Override
    public boolean usesOpenAlOutput() {
        return true;
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
        return new OpenALTappedAudioInputStream(decoded, stereo, this::close);
    }

}
