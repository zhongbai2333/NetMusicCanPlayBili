package com.zhongbai233.net_music_can_play_bili.bili.codec;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * H.264 视频原生解码器。
 *
 * 用法:
 * <pre>{@code
 *   try (VideoNativeDecoder dec = new VideoNativeDecoder(40, 22)) {
 *       for (byte[] nalUnit : h264Stream) {
 *           dec.sendPacket(nalUnit);
 *           byte[] rgba = dec.getVideoFrame();
 *           if (rgba != null) {
 *               // rgba 为 RGBA packed, width*height*4 bytes
 *           }
 *       }
 *   }
 * }</pre>
 */
public class VideoNativeDecoder implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();

    private long handle;
    private final int targetWidth;
    private final int targetHeight;
    private boolean open;
    private int originalWidth;
    private int originalHeight;
    private long totalFrames;

    public VideoNativeDecoder(int targetWidth, int targetHeight) {
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
    }

    /** 确保 FFmpeg 和 JNI 库已加载 */
    public static void ensureLoaded() {
        Eac3NativeDecoder.preload();  // 复用现有 native 加载逻辑
    }

    /** 原生解码器是否可用 */
    public static boolean isNativeAvailable() {
        return Eac3NativeDecoder.isNativeAvailable();
    }

    private boolean ensureOpen() {
        if (open && handle != 0)
            return true;
        if (!isNativeAvailable())
            return false;

        handle = VideoJni.decoderOpen(targetWidth, targetHeight);
        if (handle == 0) {
            LOGGER.error("VideoNativeDecoder: decoderOpen 失败");
            return false;
        }
        open = true;
        LOGGER.debug("VideoNativeDecoder: 解码器已打开 (target {}x{})",
                targetWidth > 0 ? targetWidth : -1,
                targetHeight > 0 ? targetHeight : -1);
        return true;
    }

    /**
     * 喂入一包 H.264 数据。
     */
    public boolean sendPacket(byte[] data, int offset, int length) {
        if (!ensureOpen())
            return false;
        return VideoJni.sendPacket(handle, data, offset, length) == 0;
    }

    public boolean sendPacket(byte[] data) {
        return sendPacket(data, 0, data.length);
    }

    /**
     * 获取一帧解码后的 RGBA 图像。
     *
     * @return RGBA packed byte[]，无帧时返回 null
     */
    public byte[] getVideoFrame() {
        if (!open || handle == 0)
            return null;

        byte[] rgba = VideoJni.getVideoFrame(handle);
        if (rgba != null) {
            totalFrames++;
            if (totalFrames == 1) {
                long dims = VideoJni.getDimensions(handle);
                originalWidth = (int) (dims >> 32);
                originalHeight = (int) dims;
                int outW = targetWidth > 0 ? targetWidth : originalWidth;
                int outH = targetHeight > 0 ? targetHeight : originalHeight;
                LOGGER.info("VideoNativeDecoder: 首帧 {}x{} → {}x{}, {} bytes",
                        originalWidth, originalHeight, outW, outH, rgba.length);
            }
        }
        return rgba;
    }

    public void flush() {
        if (open && handle != 0) {
            VideoJni.flush(handle);
        }
    }

    @Override
    public void close() {
        open = false;
        if (handle != 0) {
            VideoJni.close(handle);
            handle = 0;
        }
        LOGGER.debug("VideoNativeDecoder: 已关闭 ({} 帧)", totalFrames);
    }

    public int getOriginalWidth() {
        return originalWidth;
    }

    public int getOriginalHeight() {
        return originalHeight;
    }

    public int getOutputWidth() {
        return targetWidth > 0 ? targetWidth : originalWidth;
    }

    public int getOutputHeight() {
        return targetHeight > 0 ? targetHeight : originalHeight;
    }

    public long totalFrames() {
        return totalFrames;
    }
}
