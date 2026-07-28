package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoBillboardPreviewBerVisibilityTest {
    @Test
    void berSubmissionExpiresAfterOneUnsubmittedFrame() {
    RecentFrameVisibility<String> visibility = new RecentFrameVisibility<>(1L);
    String projector = "173,61,-284";

    visibility.beginFrame();
    visibility.markSubmitted(projector);
    assertTrue(visibility.wasRecentlySubmitted(projector));

    visibility.beginFrame();
    assertTrue(visibility.wasRecentlySubmitted(projector),
                "上一帧标记应覆盖 BER 与全局几何事件的顺序差异");

    visibility.beginFrame();
    assertFalse(visibility.wasRecentlySubmitted(projector),
                "不再通过视锥提交后，BER 可见性必须过期");
    }

    @Test
    void unsubmittedProjectorIsNotVisible() {
    RecentFrameVisibility<String> visibility = new RecentFrameVisibility<>(1L);
    assertFalse(visibility.wasRecentlySubmitted("-991,72,443"));
    }

    @Test
    void equalProjectorPositionsRemainIsolatedBySessionKey() {
        RecentFrameVisibility<String> visibility = new RecentFrameVisibility<>(1L);
        visibility.beginFrame();
        visibility.markSubmitted("minecart-a|0,0,0");

        assertTrue(visibility.wasRecentlySubmitted("minecart-a|0,0,0"));
        assertFalse(visibility.wasRecentlySubmitted("minecart-b|0,0,0"));
    }
}