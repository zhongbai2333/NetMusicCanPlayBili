package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoRgbaRenderPassTest {
    @Test
    void fullOpacityUsesTheArtworkSpecificPass() {
        assertEquals(VideoRgbaRenderPass.SOLID,
                VideoRgbaRenderPass.choose(VideoRgbaRenderPass.TextureAlpha.OPAQUE, 1.0F));
        assertEquals(VideoRgbaRenderPass.CUTOUT,
                VideoRgbaRenderPass.choose(VideoRgbaRenderPass.TextureAlpha.BINARY_CUTOUT, 1.0F));
        assertEquals(VideoRgbaRenderPass.TRANSLUCENT,
                VideoRgbaRenderPass.choose(VideoRgbaRenderPass.TextureAlpha.TRANSLUCENT, 1.0F));
    }

    @Test
    void activeFadeRequiresBlendingRegardlessOfArtworkAlpha() {
        for (VideoRgbaRenderPass.TextureAlpha textureAlpha : VideoRgbaRenderPass.TextureAlpha.values()) {
            assertEquals(VideoRgbaRenderPass.TRANSLUCENT,
                    VideoRgbaRenderPass.choose(textureAlpha, 0.5F), textureAlpha.name());
        }
    }

    @Test
    void invisibleOrInvalidOpacitySkipsSubmission() {
        assertEquals(VideoRgbaRenderPass.SKIP,
                VideoRgbaRenderPass.choose(VideoRgbaRenderPass.TextureAlpha.OPAQUE, 0.0F));
        assertEquals(VideoRgbaRenderPass.SKIP,
                VideoRgbaRenderPass.choose(VideoRgbaRenderPass.TextureAlpha.OPAQUE, Float.NaN));
    }

    @Test
    void everyDrawablePassWritesDepthAndOnlyTranslucentBlends() {
        assertPass(VideoRgbaRenderPass.SOLID, false, 0.0F);
        assertPass(VideoRgbaRenderPass.CUTOUT, false, 0.1F);
        assertPass(VideoRgbaRenderPass.TRANSLUCENT, true, 0.01F);
    }

    private static void assertPass(VideoRgbaRenderPass renderPass, boolean blending, float alphaCutout) {
        assertEquals(blending, renderPass.blending());
        assertEquals(alphaCutout, renderPass.alphaCutout());
        assertTrue(renderPass.depthWrite());
    }
}
