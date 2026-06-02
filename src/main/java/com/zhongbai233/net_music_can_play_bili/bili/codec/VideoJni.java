package com.zhongbai233.net_music_can_play_bili.bili.codec;

/**
 * 视频解码 JNI 声明。
 */
final class VideoJni {

    private VideoJni() {
    }

    /**
     * 创建 H.264 视频解码器。
     *
     * @param targetWidth  输出宽度 (0=原始)
     * @param targetHeight 输出高度 (0=原始)
     * @return 不透明句柄，失败返回 0
     */
    static native long decoderOpen(int targetWidth, int targetHeight);

    /**
     * 喂入一包 H.264 数据（NAL 单元或 annex-b 流）。
     *
     * @return 0=成功, -1=失败
     */
    static native int sendPacket(long handle, byte[] data, int offset, int length);

    /**
     * 获取一帧解码后的 RGBA 图像。
     *
     * @return RGBA packed byte[] (width*height*4)，无帧时返回 null
     */
    static native byte[] getVideoFrame(long handle);

    /** 刷新解码器（seek 后调用） */
    static native void flush(long handle);

    /** 关闭解码器 */
    static native void close(long handle);

    /**
     * 获取原始视频尺寸。
     *
     * @return width << 32 | height
     */
    static native long getDimensions(long handle);
}
