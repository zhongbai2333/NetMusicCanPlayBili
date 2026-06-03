package com.zhongbai233.net_music_can_play_bili.media.codec;

/**
 * E-AC-3 JNI 解码器
 */
final class Eac3Jni {

    private Eac3Jni() {
    }

    /** 创建并打开 E-AC-3 解码器，返回不透明句柄（即 DecoderHandle*） 失败返回 0 */
    static native long decoderOpen();

    /**
     * 解码一帧 E-AC-3。
     *
     * @param handle decoderOpen 返回的句柄
     * @param data   E-AC-3 原始数据
     * @param offset 数据起始偏移
     * @param length 数据长度
     * @return planar float PCM [channel][sample] 失败返回 null
     */
    static native float[][] decode(long handle, byte[] data, int offset, int length);

    /** 刷新解码器内部缓冲（seek / 切歌后调用） */
    static native void flush(long handle);

    /** 关闭解码器，释放全部 FFmpeg 资源 */
    static native void close(long handle);

    /** FFmpeg 版本字符串（如 "8.0.1"） */
    static native String version();
}
