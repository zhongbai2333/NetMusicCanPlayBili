package com.zhongbai233.net_music_can_play_bili.media.codec;

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
     * 创建指定编码的视频解码器。
     *
     * @param codecId B站 DASH codecid: 7=H.264, 12=HEVC
     */
    static native long decoderOpenForCodec(int codecId, int targetWidth, int targetHeight);

    /**
     * 创建指定编码的视频解码器，并请求 FFmpeg 通用硬件加速。
     *
     * @param hwaccel auto/none/off/d3d11va/cuda/vaapi/videotoolbox 等 FFmpeg hwdevice 名称
     */
    static native long decoderOpenForCodecWithHwaccel(int codecId, int targetWidth, int targetHeight, String hwaccel);

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

    /**
     * 获取一帧解码后的 YUV420P 图像。
     *
     * @return packed Y + U + V byte[] (width*height*3/2)，无帧时返回 null
     */
    static native byte[] getVideoFrameYuv420(long handle);

    /**
     * 接收并丢弃一帧解码结果，不执行硬件帧回读、sws_scale 或 Java byte[] 拷贝。
     *
     * @return 1=收到并丢弃一帧，0=暂时无帧/EOF，-1=失败
     */
    static native int receiveFrameNoCopy(long handle);

    /** 刷新解码器（seek 后调用） */
    static native void flush(long handle);

    /** 查询实际启用的 FFmpeg 硬解后端；未启用或旧 native 缺少符号时上层回退为 unknown/cpu。 */
    static native String getHwaccelName(long handle);

    /** 关闭解码器 */
    static native void close(long handle);

    /**
     * 获取原始视频尺寸。
     *
     * @return width << 32 | height
     */
    static native long getDimensions(long handle);
}
