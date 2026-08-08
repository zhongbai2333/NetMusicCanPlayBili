package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/** 每次视频几何提交的透明度路由；不修改共享帧、纹理或播放实例。 */
public enum VideoOpacityRoute {
    SKIP,
    TRANSLUCENT,
    OPAQUE;

    public static VideoOpacityRoute choose(float opacity) {
        float normalized = normalize(opacity);
        if (normalized <= 0.0F) return SKIP;
        if (normalized >= 1.0F) return OPAQUE;
        return TRANSLUCENT;
    }

    public static float normalize(float opacity) {
        if (Float.isNaN(opacity) || opacity == Float.NEGATIVE_INFINITY) return 0.0F;
        if (opacity == Float.POSITIVE_INFINITY) return 1.0F;
        return Math.clamp(opacity, 0.0F, 1.0F);
    }

    public static int whiteVertexColor(float opacity) {
        int alpha = Math.round(normalize(opacity) * 255.0F);
        return alpha << 24 | 0x00FFFFFF;
    }
}