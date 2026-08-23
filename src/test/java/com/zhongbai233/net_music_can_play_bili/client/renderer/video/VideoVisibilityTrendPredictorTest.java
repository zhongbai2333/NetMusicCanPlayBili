package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoVisibilityTrendPredictorTest {
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
}
