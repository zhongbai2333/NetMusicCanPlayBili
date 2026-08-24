package com.zhongbai233.net_music_can_play_bili.media;

/** Intrinsic video-surface brightness. This changes pixel RGB, never world light. */
public final class VideoSurfaceBrightness {
    public static final float MIN = 0.0F;
    public static final float MAX = 1.0F;
    public static final float DEFAULT = 1.0F;

    private VideoSurfaceBrightness() {
    }

    public static float normalize(float value) {
        if (Float.isNaN(value)) {
            return DEFAULT;
        }
        return Math.clamp(value, MIN, MAX);
    }

    public static int vertexColor(float brightness, float opacity) {
        int level = Math.round(normalize(brightness) * 255.0F);
        int alpha = Math.round(Math.clamp(Float.isNaN(opacity) ? 1.0F : opacity, 0.0F, 1.0F) * 255.0F);
        return alpha << 24 | level << 16 | level << 8 | level;
    }
}
