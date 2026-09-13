package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/**
 * Chooses the RGBA surface pass from both the artwork alpha contract and the
 * per-submission opacity. Ordinary artwork stays opaque; blending is reserved
 * for real partial alpha or an active fade.
 */
enum VideoRgbaRenderPass {
    SKIP(false, 0.0F, false),
    SOLID(false, 0.0F, true),
    CUTOUT(false, 0.1F, true),
    TRANSLUCENT(true, 0.01F, true);

    private final boolean blending;
    private final float alphaCutout;
    private final boolean depthWrite;

    VideoRgbaRenderPass(boolean blending, float alphaCutout, boolean depthWrite) {
        this.blending = blending;
        this.alphaCutout = alphaCutout;
        this.depthWrite = depthWrite;
    }

    static VideoRgbaRenderPass choose(TextureAlpha textureAlpha, float opacity) {
        VideoOpacityRoute opacityRoute = VideoOpacityRoute.choose(opacity);
        if (opacityRoute == VideoOpacityRoute.SKIP) {
            return SKIP;
        }
        if (opacityRoute == VideoOpacityRoute.TRANSLUCENT) {
            return TRANSLUCENT;
        }
        return switch (textureAlpha) {
            case OPAQUE -> SOLID;
            case BINARY_CUTOUT -> CUTOUT;
            case TRANSLUCENT -> VideoRgbaRenderPass.TRANSLUCENT;
        };
    }

    boolean blending() {
        return blending;
    }

    float alphaCutout() {
        return alphaCutout;
    }

    boolean depthWrite() {
        return depthWrite;
    }

    enum TextureAlpha {
        OPAQUE,
        BINARY_CUTOUT,
        TRANSLUCENT
    }
}
