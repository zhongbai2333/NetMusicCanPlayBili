package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoFirstFrameRecoveryPolicyTest {
    @Test
    void healthyOrStillStartingInstanceKeepsWaiting() {
        assertEquals(VideoFirstFrameRecoveryPolicy.Decision.WAIT,
                VideoFirstFrameRecoveryPolicy.decide(true, false, false, 19_999L, 20_000L, 0, 2));
        assertEquals(VideoFirstFrameRecoveryPolicy.Decision.WAIT,
                VideoFirstFrameRecoveryPolicy.decide(true, true, false, 60_000L, 20_000L, 0, 2));
        assertEquals(VideoFirstFrameRecoveryPolicy.Decision.WAIT,
                VideoFirstFrameRecoveryPolicy.decide(true, false, true, 60_000L, 20_000L, 0, 2));
    }

    @Test
    void stalledInstanceGetsBoundedRecoveryAttempts() {
        assertEquals(VideoFirstFrameRecoveryPolicy.Decision.RESTART,
                VideoFirstFrameRecoveryPolicy.decide(true, false, false, 20_000L, 20_000L, 0, 2));
        assertEquals(VideoFirstFrameRecoveryPolicy.Decision.RESTART,
                VideoFirstFrameRecoveryPolicy.decide(true, false, false, 40_000L, 20_000L, 1, 2));
        assertEquals(VideoFirstFrameRecoveryPolicy.Decision.FAIL,
                VideoFirstFrameRecoveryPolicy.decide(true, false, false, 60_000L, 20_000L, 2, 2));
    }
}