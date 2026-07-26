package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
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

    /**
     * 投递一个已完整读入的音频帧，供 FLV 这类逐帧容器使用。
     *
     * <p>
     * fMP4 以 moof 声明整段 fragment 的样本长度、再由 mdat 提供负载；逐帧容器等价于
     * 一个只含单个样本的 fragment。
     * </p>
     */
    default long onAudioFrame(byte[] frame) throws IOException {
        onMoof(new int[] { frame.length });
        return onMdat(new ByteArrayInputStream(frame), frame.length);
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
