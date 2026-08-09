package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoSurfacePrivacyPolicyTest {
    @Test
    void glassesHideAllPublicVideoSurfaces() {
        assertTrue(VideoSurfacePrivacyPolicy.hideVideo(true,
                VideoSurfacePrivacyPolicy.SurfaceKind.PUBLIC_PROJECTOR));
        assertTrue(VideoSurfacePrivacyPolicy.hideVideo(true,
                VideoSurfacePrivacyPolicy.SurfaceKind.CONTROL_CONSOLE));
    }

    @Test
    void glassesKeepTheirPrivateScreenVisible() {
        assertFalse(VideoSurfacePrivacyPolicy.hideVideo(true,
                VideoSurfacePrivacyPolicy.SurfaceKind.PRIVATE_HOLOGRAPHIC));
    }

    @Test
    void publicSurfacesRemainVisibleWithoutGlassesPrivacy() {
        assertFalse(VideoSurfacePrivacyPolicy.hideVideo(false,
                VideoSurfacePrivacyPolicy.SurfaceKind.PUBLIC_PROJECTOR));
        assertFalse(VideoSurfacePrivacyPolicy.hideVideo(false,
                VideoSurfacePrivacyPolicy.SurfaceKind.CONTROL_CONSOLE));
    }
}