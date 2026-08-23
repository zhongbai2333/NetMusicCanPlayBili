package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoVisualSyncPolicyTest {
    @Test
    void visibleOrPredictedSessionParticipatesInSynchronization() {
        assertTrue(VideoVisualSyncPolicy.active(true, false, true, false));
        assertEquals("ACTIVE", VideoVisualSyncPolicy.debugStatus(true));
    }

    @Test
    void noVisualDemandSuspendsSynchronizationBeforeDecodePauseGraceExpires() {
        assertFalse(VideoVisualSyncPolicy.active(true, false, false, false));
        assertEquals("SUSPENDED_OFFSCREEN", VideoVisualSyncPolicy.debugStatus(false));
    }

    @Test
    void committedDecoderPauseAlsoSuspendsSynchronization() {
        assertFalse(VideoVisualSyncPolicy.active(true, false, true, true));
    }

    @Test
    void stoppedOrFailedSessionNeverParticipatesInSynchronization() {
        assertFalse(VideoVisualSyncPolicy.active(false, false, true, false));
        assertFalse(VideoVisualSyncPolicy.active(true, true, true, false));
    }
}
