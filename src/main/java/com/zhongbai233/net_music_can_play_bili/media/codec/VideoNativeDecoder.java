package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * H.264/HEVC 视频原生解码器。
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
    private static final String DEFAULT_HWACCEL = "auto";

    private long handle;
    private final int codecId;
    private final int targetWidth;
    private final int targetHeight;
    private boolean open;
    private String requestedHwaccel = DEFAULT_HWACCEL;
    private int originalWidth;
    private int originalHeight;
    private long totalFrames;
    private String actualHwaccel = "unknown";

    public VideoNativeDecoder(int targetWidth, int targetHeight) {
        this(7, targetWidth, targetHeight);
    }

    /**
     * @param codecId B站 DASH codecid: 7=H.264, 12=HEVC
     */
    public VideoNativeDecoder(int codecId, int targetWidth, int targetHeight) {
        this.codecId = codecId;
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

    public void setRequestedHwaccel(String requestedHwaccel) {
        if (!open) {
            this.requestedHwaccel = requestedHwaccel == null || requestedHwaccel.isBlank() ? DEFAULT_HWACCEL : requestedHwaccel.trim();
        }
    }

    private boolean ensureOpen() {
        if (open && handle != 0)
            return true;
        if (!isNativeAvailable())
            return false;

        handle = isHwaccelRequested()
            ? VideoJni.decoderOpenForCodecWithHwaccel(codecId, targetWidth, targetHeight, requestedHwaccel)
            : codecId == 7
                ? VideoJni.decoderOpen(targetWidth, targetHeight)
                : VideoJni.decoderOpenForCodec(codecId, targetWidth, targetHeight);
        if (handle == 0) {
            LOGGER.error("VideoNativeDecoder: decoderOpen 失败 codecId={}, hwaccel={}", codecId,
                isHwaccelRequested() ? requestedHwaccel : "none");
            return false;
        }
        actualHwaccel = queryActualHwaccel(handle);
        open = true;
        LOGGER.info("VideoNativeDecoder: 解码器已打开 codecId={}, requestedHwaccel={}, actualHwaccel={} (target {}x{})", codecId,
            isHwaccelRequested() ? requestedHwaccel : "none",
            actualHwaccel,
                targetWidth > 0 ? targetWidth : -1,
                targetHeight > 0 ? targetHeight : -1);
        return true;
    }

        private boolean isHwaccelRequested() {
        return !requestedHwaccel.isBlank()
            && !"none".equalsIgnoreCase(requestedHwaccel)
            && !"off".equalsIgnoreCase(requestedHwaccel);
        }

        private static String queryActualHwaccel(long handle) {
            try {
                String value = VideoJni.getHwaccelName(handle);
                return value == null || value.isBlank() ? "unknown" : value;
            } catch (UnsatisfiedLinkError e) {
                return "unknown-old-native";
            } catch (Throwable e) {
                LOGGER.warn("VideoNativeDecoder: 查询实际硬解后端失败", e);
                return "unknown-error";
            }
        }

    /**
     * 喂入一包 H.264 数据。
     */
    public synchronized boolean sendPacket(byte[] data, int offset, int length) {
        if (!ensureOpen())
            return false;
        if (!open || handle == 0)
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
    public synchronized byte[] getVideoFrame() {
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
                LOGGER.info("VideoNativeDecoder: 首帧 {}x{} → {}x{}, {} bytes, actualHwaccel={}",
                    originalWidth, originalHeight, outW, outH, rgba.length, actualHwaccel);
            }
        }
        return rgba;
    }

    /**
     * 获取一帧解码后的 YUV420P 图像。
     *
     * <p>这条路径仍会在硬解时把 frame transfer 到 CPU，但 sws_scale 输出和 Java byte[] 大小
     * 从 RGBA 的 4 bytes/pixel 降为 YUV420 的 1.5 bytes/pixel，便于验证 YUV+shader 方案的真实收益。</p>
     */
    public synchronized byte[] getVideoFrameYuv420() {
        if (!open || handle == 0) {
            return null;
        }
        byte[] yuv;
        try {
            yuv = VideoJni.getVideoFrameYuv420(handle);
        } catch (UnsatisfiedLinkError oldNative) {
            LOGGER.warn("VideoNativeDecoder: 当前 native 缺少 getVideoFrameYuv420，回退 RGBA", oldNative);
            return getVideoFrame();
        }
        if (yuv != null) {
            totalFrames++;
            if (totalFrames == 1) {
                long dims = VideoJni.getDimensions(handle);
                originalWidth = (int) (dims >> 32);
                originalHeight = (int) dims;
                int outW = targetWidth > 0 ? targetWidth : originalWidth;
                int outH = targetHeight > 0 ? targetHeight : originalHeight;
                LOGGER.info("VideoNativeDecoder: 首帧(YUV420) {}x{} → {}x{}, {} bytes, actualHwaccel={}",
                    originalWidth, originalHeight, outW, outH, yuv.length, actualHwaccel);
            }
        }
        return yuv;
    }

    /**
     * 仅接收并丢弃一帧，用于纯解码压力测试。
     *
     * <p>这条路径不会把硬件帧 transfer 到 CPU，也不会做 RGBA sws_scale / Java byte[] 分配。
     * 如果当前 native 还没有 receiveFrameNoCopy 符号，则回退到 getVideoFrame()，保证旧 native 不崩。</p>
     */
    public synchronized boolean receiveFrameNoCopy() {
        if (!open || handle == 0) {
            return false;
        }
        try {
            int status = VideoJni.receiveFrameNoCopy(handle);
            if (status <= 0) {
                return false;
            }
            totalFrames++;
            if (totalFrames == 1) {
                long dims = VideoJni.getDimensions(handle);
                originalWidth = (int) (dims >> 32);
                originalHeight = (int) dims;
                int outW = targetWidth > 0 ? targetWidth : originalWidth;
                int outH = targetHeight > 0 ? targetHeight : originalHeight;
                LOGGER.info("VideoNativeDecoder: 首帧(no-copy) {}x{} → {}x{}, actualHwaccel={}",
                    originalWidth, originalHeight, outW, outH, actualHwaccel);
            }
            return true;
        } catch (UnsatisfiedLinkError oldNative) {
            return getVideoFrame() != null;
        }
    }

    public synchronized void flush() {
        if (open && handle != 0) {
            VideoJni.flush(handle);
        }
    }

    @Override
    public synchronized void close() {
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
