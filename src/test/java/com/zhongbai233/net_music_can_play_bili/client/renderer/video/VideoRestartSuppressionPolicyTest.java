package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoRestartSuppressionPolicyTest {
    @Test
    void liveStreamKeepsConsumingWhileOffscreen() {
        assertFalse(VideoRestartSuppressionPolicy.shouldPauseOffscreen(true, true));
        assertTrue(VideoRestartSuppressionPolicy.shouldPauseOffscreen(false, true));
        assertFalse(VideoRestartSuppressionPolicy.shouldPauseOffscreen(false, false));
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