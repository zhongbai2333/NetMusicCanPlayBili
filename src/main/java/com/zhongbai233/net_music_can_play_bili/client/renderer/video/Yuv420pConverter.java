package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;

/**
 * YUV420P/I420 帧转换工具。
 *
 * <p>
 * 主要作为诊断、基准测试和 shader 不可用时的 CPU RGBA 回退路径；正常视频渲染优先使用
 * NV12/YUV 多平面纹理和 fragment shader。
 * </p>
 */
public final class Yuv420pConverter {
    private static final String MATRIX = System.getProperty("bili.video.yuv.matrix", "bt709_limited")
            .trim().toLowerCase(java.util.Locale.ROOT);

    private Yuv420pConverter() {
    }

    public static byte[] toUploadRgba(Fmp4NativeVideoDecoder.DecodedFrame frame, int width, int height) {
        if (frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA) {
            return frame.rgba();
        }
        if (frame.format() != Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P) {
            if (frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12) {
                return nv12ToRgba(frame.data(), width, height);
            }
            throw new IllegalArgumentException("unsupported video frame format: " + frame.format());
        }
        return yuv420pToRgba(frame.data(), width, height);
    }

    static byte[] toUploadRgba(VideoBillboardPreview.DecodedFrame frame, int width, int height) {
        if (frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA) {
            return frame.rgba();
        }
        if (frame.format() != Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P) {
            if (frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12) {
                return nv12ToRgba(frame.data(), width, height);
            }
            throw new IllegalArgumentException("unsupported video frame format: " + frame.format());
        }
        return yuv420pToRgba(frame.data(), width, height);
    }

    public static byte[] yuv420pToRgba(byte[] yuv, int width, int height) {
        if ((width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("YUV420P requires even dimensions: " + width + "x" + height);
        }
        int ySize = width * height;
        int uvWidth = width / 2;
        int uvHeight = height / 2;
        int uvSize = uvWidth * uvHeight;
        int required = ySize + uvSize * 2;
        if (yuv.length < required) {
            throw new IllegalArgumentException("YUV420P frame too small: " + yuv.length + " < " + required);
        }

        byte[] rgba = new byte[ySize * 4];
        int uBase = ySize;
        int vBase = ySize + uvSize;
        int out = 0;
        for (int y = 0; y < height; y++) {
            int yRow = y * width;
            int uvRow = (y / 2) * uvWidth;
            for (int x = 0; x < width; x++) {
                int yy = yuv[yRow + x] & 0xFF;
                int uu = yuv[uBase + uvRow + x / 2] & 0xFF;
                int vv = yuv[vBase + uvRow + x / 2] & 0xFF;
                int[] rgb = convert(yy, uu, vv);
                rgba[out++] = (byte) rgb[0];
                rgba[out++] = (byte) rgb[1];
                rgba[out++] = (byte) rgb[2];
                rgba[out++] = (byte) 255;
            }
        }
        return rgba;
    }

    public static byte[] nv12ToRgba(byte[] nv12, int width, int height) {
        if ((width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("NV12 requires even dimensions: " + width + "x" + height);
        }
        int ySize = width * height;
        int required = ySize + ySize / 2;
        if (nv12.length < required) {
            throw new IllegalArgumentException("NV12 frame too small: " + nv12.length + " < " + required);
        }

        byte[] rgba = new byte[ySize * 4];
        int uvBase = ySize;
        int out = 0;
        for (int y = 0; y < height; y++) {
            int yRow = y * width;
            int uvRow = (y / 2) * width;
            for (int x = 0; x < width; x++) {
                int yy = nv12[yRow + x] & 0xFF;
                int uv = uvBase + uvRow + (x & ~1);
                int uu = nv12[uv] & 0xFF;
                int vv = nv12[uv + 1] & 0xFF;
                int[] rgb = convert(yy, uu, vv);
                rgba[out++] = (byte) rgb[0];
                rgba[out++] = (byte) rgb[1];
                rgba[out++] = (byte) rgb[2];
                rgba[out++] = (byte) 255;
            }
        }
        return rgba;
    }

    public static int nv12PixelArgb(byte[] nv12, int width, int height, int x, int y) {
        if (nv12 == null || width <= 0 || height <= 0) {
            return 0xFF000000;
        }
        int safeX = Math.max(0, Math.min(width - 1, x));
        int safeY = Math.max(0, Math.min(height - 1, y));
        int ySize = width * height;
        int uv = ySize + (safeY / 2) * width + (safeX & ~1);
        if (nv12.length <= uv + 1 || nv12.length <= safeY * width + safeX) {
            return 0xFF000000;
        }
        int yy = nv12[safeY * width + safeX] & 0xFF;
        int uu = nv12[uv] & 0xFF;
        int vv = nv12[uv + 1] & 0xFF;
        int[] rgb = convert(yy, uu, vv);
        return 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
    }

    public static int yuv420pPixelArgb(byte[] yuv, int width, int height, int x, int y) {
        if (yuv == null || width <= 0 || height <= 0) {
            return 0xFF000000;
        }
        int safeX = Math.max(0, Math.min(width - 1, x));
        int safeY = Math.max(0, Math.min(height - 1, y));
        int ySize = width * height;
        int uvWidth = Math.max(1, width / 2);
        int uvHeight = Math.max(1, height / 2);
        int uvSize = uvWidth * uvHeight;
        int u = ySize + (safeY / 2) * uvWidth + safeX / 2;
        int v = ySize + uvSize + (safeY / 2) * uvWidth + safeX / 2;
        if (yuv.length <= v || yuv.length <= safeY * width + safeX) {
            return 0xFF000000;
        }
        int yy = yuv[safeY * width + safeX] & 0xFF;
        int uu = yuv[u] & 0xFF;
        int vv = yuv[v] & 0xFF;
        int[] rgb = convert(yy, uu, vv);
        return 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
    }

    private static int[] convert(int y, int u, int v) {
        return switch (MATRIX) {
            case "bt601", "bt601_limited" -> limitedBt601(y, u, v);
            case "full", "bt709_full", "full_range" -> fullRangeBt709(y, u, v);
            default -> limitedBt709(y, u, v);
        };
    }

    private static int[] limitedBt709(int y, int u, int v) {
        int c = Math.max(0, y - 16);
        int d = u - 128;
        int e = v - 128;
        int r = (298 * c + 459 * e + 128) >> 8;
        int g = (298 * c - 55 * d - 136 * e + 128) >> 8;
        int b = (298 * c + 541 * d + 128) >> 8;
        return new int[] { clamp(r), clamp(g), clamp(b) };
    }

    private static int[] limitedBt601(int y, int u, int v) {
        int c = Math.max(0, y - 16);
        int d = u - 128;
        int e = v - 128;
        int r = (298 * c + 409 * e + 128) >> 8;
        int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
        int b = (298 * c + 516 * d + 128) >> 8;
        return new int[] { clamp(r), clamp(g), clamp(b) };
    }

    private static int[] fullRangeBt709(int y, int u, int v) {
        int d = u - 128;
        int e = v - 128;
        int r = Math.round((float) (y + 1.5748D * e));
        int g = Math.round((float) (y - 0.1873D * d - 0.4681D * e));
        int b = Math.round((float) (y + 1.8556D * d));
        return new int[] { clamp(r), clamp(g), clamp(b) };
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : value > 255 ? 255 : value;
    }
}