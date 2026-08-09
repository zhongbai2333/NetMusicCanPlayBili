package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoYuvRenderPolicyTest {
    @AfterEach
    void clearProperty() {
        System.clearProperty(VideoYuvRenderPolicy.NO_DEPTH_WRITE_PROPERTY);
    }

    @Test
    void yuvVideoUsesSolidFeatureStageWhileKeepingItsBlendPipeline() {
        assertTrue(VideoYuvRenderPolicy.useSolidFeatureStage());
    }

    @Test
    void depthWriteRemainsEnabledByDefault() {
        assertFalse(VideoYuvRenderPolicy.disableDepthWrite());
    }

    @Test
    void diagnosticOverrideCanDisableDepthWriteExplicitly() {
        System.setProperty(VideoYuvRenderPolicy.NO_DEPTH_WRITE_PROPERTY, "true");

        assertTrue(VideoYuvRenderPolicy.disableDepthWrite());
    }
}