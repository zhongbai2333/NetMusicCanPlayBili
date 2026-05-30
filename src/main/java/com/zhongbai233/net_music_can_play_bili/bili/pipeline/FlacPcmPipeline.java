package com.zhongbai233.net_music_can_play_bili.bili.pipeline;

import com.github.tartaricacid.netmusic.soundlibs.org.jflac.sound.spi.Flac2PcmAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.bili.stream.BlockingAudioPipe;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.io.InputStream;

/**
 * 普通 NetMusic 唱片机使用的 FLAC 管线：拼回原生 FLAC 流，再交给 NetMusic 的 JFLAC 解码
 */
public final class FlacPcmPipeline extends AbstractAudioPipeline {
    private final AudioFormat format;
    private final BlockingAudioPipe output;
    private long compressedBytes;

    public FlacPcmPipeline(byte[] dfLa, BlockingAudioPipe output) throws IOException {
        super("fMP4", "flac", null, false);
        this.format = FlacStreamSupport.audioFormat(dfLa);
        this.output = output;
        FlacStreamSupport.writeNativeHeader(output, dfLa);
    }

    @Override
    public AudioFormat format() {
        return format;
    }

    @Override
    public String detail() {
        return format.getSampleSizeInBits() > 16 ? "Hi-Res FLAC via NetMusic dither" : "FLAC via NetMusic";
    }

    @Override
    public long onMdat(InputStream input, long length) throws IOException {
        long copied = FlacStreamSupport.copyMdat(input, length, output, null);
        compressedBytes += copied;
        return copied;
    }

    @Override
    public String statsSummary() {
        return "flacCompressedBytes=" + compressedBytes;
    }

    @Override
    public void finish() {
        output.closeWriter();
    }

    @Override
    public void close() {
        output.closeWriter();
    }

    public AudioInputStream openDecodedStream() {
        return new Flac2PcmAudioInputStream(output, format, AudioSystem.NOT_SPECIFIED);
    }

}
