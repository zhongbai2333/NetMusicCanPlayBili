package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoBerConsumerVisibilityPolicyTest {
    @Test
    void recentConsoleSubmissionUsesBerVisibilityWithoutProjectorRegistration() {
        assertTrue(VideoBerConsumerVisibilityPolicy.usesBerSubmission(false, true));
    }

    @Test
    void managedProjectorStillUsesBerVisibilityWhileTemporarilyUnsubmitted() {
        assertTrue(VideoBerConsumerVisibilityPolicy.usesBerSubmission(true, false));
    }

    @Test
    void unknownUnsubmittedPositionFallsBackToBlockEntityVisibility() {
        assertFalse(VideoBerConsumerVisibilityPolicy.usesBerSubmission(false, false));
    }
}