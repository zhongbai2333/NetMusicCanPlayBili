package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;

/**
 * 普通 NetMusic 唱片机使用的 AAC 解码管线：只输出 PCM，不接管 OpenAL
 */
public final class AacPcmPipeline extends AbstractAudioPipeline {
    private final AacFrameDecoder decoder;
    private final BlockingAudioPipe output;

    public AacPcmPipeline(byte[] asc, BlockingAudioPipe output) {
        super("fMP4", "aac", "NetMusic-compatible PCM", false);
        this.decoder = new AacFrameDecoder(asc, null);
        this.output = output;
    }

    @Override
    public AudioFormat format() {
        return decoder.format();
    }

    @Override
    public void onMoof(int[] sampleSizes) {
        decoder.onMoof(sampleSizes);
    }

    @Override
    public long onMdat(InputStream input, long length) throws IOException {
        return decoder.onMdat(input, length, (pcm, format) -> output.write(pcm));
    }

    @Override
    public long decodedFrames() {
        return decoder.decodedFrames();
    }

    @Override
    public String statsSummary() {
        return "aacFrames=" + decoder.decodedFrames();
    }

    @Override
    public void close() {
        output.closeWriter();
    }

}
