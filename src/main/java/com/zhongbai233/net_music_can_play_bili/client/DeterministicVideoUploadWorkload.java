package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;

/** Deterministic local frames for GPU upload benchmarks; performs no network or decoder work. */
public final class DeterministicVideoUploadWorkload {
    private DeterministicVideoUploadWorkload() {
    }

    public static byte[] frame(VideoBillboardPreview.BenchUploadFormat format,
            int width, int height, int frameIndex) {
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("bench dimensions must be positive and even");
        }
        return switch (java.util.Objects.requireNonNull(format, "format")) {
            case RGBA -> rgba(width, height, frameIndex);
            case YUV420P -> yuv420p(width, height, frameIndex);
            case NV12 -> nv12(width, height, frameIndex);
        };
    }

    private static byte[] rgba(int width, int height, int frameIndex) {
        byte[] frame = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
        int phase = frameIndex * 8;
        for (int y = 0; y < height; y++) {
            int gradient = y * 255 / Math.max(1, height - 1);
            for (int x = 0; x < width; x++) {
                int bar = ((x + phase) * 8 / width) & 7;
                int i = (y * width + x) * 4;
                frame[i] = (byte) ((bar & 1) == 0 ? 255 : gradient);
                frame[i + 1] = (byte) ((bar & 2) == 0 ? gradient : 255);
                frame[i + 2] = (byte) ((bar & 4) == 0 ? gradient : 255);
                frame[i + 3] = (byte) 255;
            }
        }
        return frame;
    }

    private static byte[] yuv420p(int width, int height, int frameIndex) {
        int pixels = Math.multiplyExact(width, height);
        byte[] frame = new byte[pixels * 3 / 2];
        fillLuma(frame, width, height, frameIndex);
        int chroma = pixels / 4;
        for (int i = 0; i < chroma; i++) {
            frame[pixels + i] = (byte) (96 + (frameIndex + i) % 64);
            frame[pixels + chroma + i] = (byte) (160 - (frameIndex + i) % 64);
        }
        return frame;
    }

    private static byte[] nv12(int width, int height, int frameIndex) {
        int pixels = Math.multiplyExact(width, height);
        byte[] frame = new byte[pixels * 3 / 2];
        fillLuma(frame, width, height, frameIndex);
        for (int i = pixels; i < frame.length; i += 2) {
            frame[i] = (byte) (96 + (frameIndex + i / 2) % 64);
            frame[i + 1] = (byte) (160 - (frameIndex + i / 2) % 64);
        }
        return frame;
    }

    private static void fillLuma(byte[] frame, int width, int height, int frameIndex) {
        int phase = frameIndex * 8;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int bar = ((x + phase) * 8 / width) & 7;
                frame[y * width + x] = (byte) (32 + bar * 25 + y * 16 / Math.max(1, height - 1));
            }
        }
    }
}