package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

/**
 * DynamicTexture 像素写入工具，集中处理 RGBA 字节流、NativeImage 公开指针直写和诊断通道映射。
 */
final class VideoFrameUploader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PIXEL_MODE = System.getProperty("bili.video.pixel.mode", "normal");
    private static final boolean FAST_NATIVE_UPLOAD = Boolean.parseBoolean(
            System.getProperty("bili.video.fast_native_upload", "true"));

    private VideoFrameUploader() {
    }

    static String pixelMode() {
        return PIXEL_MODE;
    }

    static boolean fastNativeUploadAvailable() {
        return FAST_NATIVE_UPLOAD;
    }

    static boolean uploadRgba(NativeImage image, byte[] rgba, int frameWidth, int frameHeight) {
        if (FAST_NATIVE_UPLOAD && uploadFrameFast(image, rgba, frameWidth, frameHeight)) {
            return true;
        }

        int i = 0;
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                int r = rgba[i] & 0xFF;
                int g = rgba[i + 1] & 0xFF;
                int b = rgba[i + 2] & 0xFF;
                int a = rgba[i + 3] & 0xFF;
                image.setPixel(x, y, packPixel(r, g, b, a));
                i += 4;
            }
        }
        return true;
    }

    static boolean uploadPackedRgbaBytes(NativeImage image, byte[] packedRgbaBytes, int textureWidth,
            int textureHeight) {
        int byteCount = textureWidth * textureHeight * 4;
        long pixels = getNativeImagePixelsAddress(image);
        if (pixels != 0L) {
            ByteBuffer nativePixels = MemoryUtil.memByteBuffer(pixels, byteCount);
            nativePixels.put(0, packedRgbaBytes, 0, byteCount);
            return true;
        }

        int i = 0;
        for (int y = 0; y < textureHeight; y++) {
            for (int x = 0; x < textureWidth; x++) {
                int r = packedRgbaBytes[i] & 0xFF;
                int g = packedRgbaBytes[i + 1] & 0xFF;
                int b = packedRgbaBytes[i + 2] & 0xFF;
                int a = packedRgbaBytes[i + 3] & 0xFF;
                image.setPixel(x, y, packPixel(r, g, b, a));
                i += 4;
            }
        }
        return true;
    }

    private static boolean uploadFrameFast(NativeImage image, byte[] rgba, int frameWidth, int frameHeight) {
        long pixels = getNativeImagePixelsAddress(image);
        if (pixels == 0L) {
            return false;
        }

        int byteCount = frameWidth * frameHeight * 4;
        if (isStraightRgbaMode()) {
            ByteBuffer nativePixels = MemoryUtil.memByteBuffer(pixels, byteCount);
            nativePixels.put(0, rgba, 0, byteCount);
            return true;
        }

        int source = 0;
        long target = pixels;
        int pixelCount = frameWidth * frameHeight;
        for (int pixel = 0; pixel < pixelCount; pixel++) {
            int r = rgba[source] & 0xFF;
            int g = rgba[source + 1] & 0xFF;
            int b = rgba[source + 2] & 0xFF;
            int a = rgba[source + 3] & 0xFF;
            MemoryUtil.memPutInt(target, packPixelABGR(r, g, b, a));
            source += 4;
            target += 4L;
        }
        return true;
    }

    private static boolean isStraightRgbaMode() {
        return switch (PIXEL_MODE) {
            case "normal", "argb", "rgba" -> true;
            default -> false;
        };
    }

    private static long getNativeImagePixelsAddress(NativeImage image) {
        if (image == null || image.isClosed()) {
            return 0L;
        }
        try {
            return image.getPointer();
        } catch (RuntimeException e) {
            LOGGER.debug("NativeImage getPointer 不可用，视频上传将回退到逐像素 setPixel 路径: {}", e.toString());
            return 0L;
        }
    }

    private static int packPixelABGR(int r, int g, int b, int a) {
        return switch (PIXEL_MODE) {
            case "normal", "argb", "rgba" -> (a << 24) | (b << 16) | (g << 8) | r;
            case "swap_rb", "bgra" -> (a << 24) | (r << 16) | (g << 8) | b;
            case "green_only" -> (a << 24) | (g << 8);
            case "blue_only" -> (a << 24) | (b << 16);
            case "red_only" -> (a << 24) | r;
            case "grayscale" -> {
                int y = (r * 30 + g * 59 + b * 11) / 100;
                yield (a << 24) | (y << 16) | (y << 8) | y;
            }
            case "debug_rgb_bars" -> {
                int rr = r > 127 ? 255 : 0;
                int gg = g > 127 ? 255 : 0;
                int bb = b > 127 ? 255 : 0;
                yield (a << 24) | (bb << 16) | (gg << 8) | rr;
            }
            default -> (a << 24) | (r << 16) | (g << 8) | b;
        };
    }

    private static int packPixel(int r, int g, int b, int a) {
        return switch (PIXEL_MODE) {
            case "normal", "argb", "rgba" -> (a << 24) | (r << 16) | (g << 8) | b;
            case "swap_rb", "bgra" -> (a << 24) | (b << 16) | (g << 8) | r;
            case "green_only" -> (a << 24) | (g << 8);
            case "blue_only" -> (a << 24) | b;
            case "red_only" -> (a << 24) | (r << 16);
            case "grayscale" -> {
                int y = (r * 30 + g * 59 + b * 11) / 100;
                yield (a << 24) | (y << 16) | (y << 8) | y;
            }
            case "debug_rgb_bars" -> {
                int rr = r > 127 ? 255 : 0;
                int gg = g > 127 ? 255 : 0;
                int bb = b > 127 ? 255 : 0;
                yield (a << 24) | (rr << 16) | (gg << 8) | bb;
            }
            default -> {
                LOGGER.warn("未知 bili.video.pixel.mode={}，回退到 swap_rb", PIXEL_MODE);
                yield (a << 24) | (b << 16) | (g << 8) | r;
            }
        };
    }
}