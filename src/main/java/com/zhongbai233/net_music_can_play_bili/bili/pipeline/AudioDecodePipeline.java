package com.zhongbai233.net_music_can_play_bili.bili.pipeline;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;

public interface AudioDecodePipeline extends AutoCloseable {
    AudioFormat format();

    String container();

    String codec();

    String detail();

    boolean usesOpenAlOutput();

    default void onMoof(int[] sampleSizes) throws IOException {
    }

    long onMdat(InputStream input, long length) throws IOException;

    default long onRawStream(InputStream input) throws IOException {
        return onMdat(input, -1L);
    }

    default long decodedFrames() {
        return 0L;
    }

    default String statsSummary() {
        return "";
    }

    default void finish() throws IOException {
    }

    @Override
    void close();
}
