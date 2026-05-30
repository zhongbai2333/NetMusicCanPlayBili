package com.zhongbai233.net_music_can_play_bili.bili.pipeline;

import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import net.minecraft.core.BlockPos;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AacOpenALPipeline implements AudioDecodePipeline {
    private final AacFrameDecoder decoder;
    private final AtomicBoolean cleaned = new AtomicBoolean(false);
    private final StereoOpenALHandler stereo;

    public AacOpenALPipeline(byte[] asc, AtomicBoolean ownerClosed) {
        this(asc, ownerClosed, null);
    }

    public AacOpenALPipeline(byte[] asc, AtomicBoolean ownerClosed, BlockPos sourcePos) {
        this.decoder = new AacFrameDecoder(asc, ownerClosed::get);
        this.stereo = new StereoOpenALHandler();
        this.stereo.setSampleRate((int) decoder.format().getSampleRate());
        DolbyAudioRegistry.registerStereo(stereo, sourcePos);
    }

    @Override
    public AudioFormat format() {
        return decoder.format();
    }

    @Override
    public String container() {
        return "fMP4";
    }

    @Override
    public String codec() {
        return "aac";
    }

    @Override
    public String detail() {
        return decoder.format().isBigEndian() ? "big-endian PCM" : "little-endian PCM";
    }

    @Override
    public boolean usesOpenAlOutput() {
        return true;
    }

    @Override
    public void onMoof(int[] sampleSizes) {
        decoder.onMoof(sampleSizes);
    }

    @Override
    public long onMdat(InputStream input, long length) throws IOException {
        return decoder.onMdat(input, length,
                (pcm, format) -> stereo.enqueuePcm(StereoOpenALHandler.shortToFloatPlanar(pcm, format.getChannels())));
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
            DolbyAudioRegistry.unregisterStereo(stereo);
            stereo.cleanup();
        }
    }

}
