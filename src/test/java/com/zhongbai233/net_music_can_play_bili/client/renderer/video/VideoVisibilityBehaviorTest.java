package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoVisibilityBehaviorTest {

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
    void equalProjectorPositionsRemainIsolatedBySessionKey() {
        RecentFrameVisibility<String> visibility = new RecentFrameVisibility<>(1L);
        visibility.beginFrame();
        visibility.markSubmitted("minecart-a|0,0,0");

        assertTrue(visibility.wasRecentlySubmitted("minecart-a|0,0,0"));
        assertFalse(visibility.wasRecentlySubmitted("minecart-b|0,0,0"));
    }

    @Test
    void movementMustBringTheActualScreenTowardTheFutureViewport() {
        assertTrue(predict(0, 0, 1, 0, 0, 0, 0, -1, -1, 29, 1, 1, 31));
        assertFalse(predict(0, 0, -1, 0, 0, 0, 0, -1, -1, 29, 1, 1, 31));
    }

    @Test
    void merelyBeingInsideVideoDistanceDoesNotPrewarmAnUnseenScreen() {
        assertFalse(predict(0, 0, 0, 0, 0, 0, 0, 29, -1, -1, 31, 1, 1));
    }

    @Test
    void cameraRotationTrendCanPrewarmBeforeTheScreenEntersView() {
        assertTrue(predict(0, 0, 0, -20, 0, -10, 0, 29, -1, -1, 31, 1, 1));
        assertFalse(predict(0, 0, 0, 20, 0, 10, 0, 29, -1, -1, 31, 1, 1));
    }

    private static boolean predict(double velocityX, double velocityY, double velocityZ,
            double yaw, double pitch, double previousYaw, double previousPitch,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return VideoVisibilityTrendPredictor.shouldPrewarm(
                0, 0, 0, velocityX, velocityY, velocityZ,
                yaw, pitch, previousYaw, previousPitch,
                minX, minY, minZ, maxX, maxY, maxZ, 64.0D, 0.12D);
    }

    @Test
    void startupCandidateNeverPausesWithAnUncommittedProbeFrame() {
        assertFalse(VideoRestartSuppressionPolicy.shouldPauseDecodeOffscreen(false, false, true));
        assertFalse(VideoRestartSuppressionPolicy.shouldPauseDecodeOffscreen(false, true, true));
        assertTrue(VideoRestartSuppressionPolicy.shouldPauseDecodeOffscreen(true, false, true));
        assertTrue(VideoRestartSuppressionPolicy.shouldPauseDecodeOffscreen(true, true, true));
        assertFalse(VideoRestartSuppressionPolicy.shouldPauseDecodeOffscreen(true, false, false));
    }

    @Test
    void liveAndDrainingDecoderCannotBeRestartedByVisibilityOrLag() {
        assertFalse(VideoRestartSuppressionPolicy.allowsRestart(true, false, 60_000L, 8_000L));
        assertFalse(VideoRestartSuppressionPolicy.allowsRestart(false, true, 60_000L, 8_000L));
    }

    @Test
    void vodDecoderGetsAStabilizationWindowAfterSeek() {
        assertFalse(VideoRestartSuppressionPolicy.allowsRestart(false, false, 7_999L, 8_000L));
        assertTrue(VideoRestartSuppressionPolicy.allowsRestart(false, false, 8_000L, 8_000L));
    }
}
