package com.zhongbai233.net_music_can_play_bili.blockentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoProjectorBlockEntityTest {
    private static final String FIRST_TURNTABLE = "1,2,3";
    private static final String SECOND_TURNTABLE = "4,5,6";

    @Test
    void unchangedClientDataDoesNotRestartPlayback() {
        assertFalse(VideoProjectorClientRefreshPolicy.shouldRefresh(
                FIRST_TURNTABLE, FIRST_TURNTABLE, 116, 116));
        assertFalse(VideoProjectorClientRefreshPolicy.shouldRefresh(null, null, 116, 116));
    }

    @Test
    void targetAdditionReplacementAndRemovalWakePlayback() {
        assertTrue(VideoProjectorClientRefreshPolicy.shouldRefresh(
                null, FIRST_TURNTABLE, 116, 116));
        assertTrue(VideoProjectorClientRefreshPolicy.shouldRefresh(
                FIRST_TURNTABLE, SECOND_TURNTABLE, 116, 116));
        assertTrue(VideoProjectorClientRefreshPolicy.shouldRefresh(
                FIRST_TURNTABLE, null, 116, 116));
    }

    @Test
    void qualityChangeStillRestartsPlayback() {
        assertTrue(VideoProjectorClientRefreshPolicy.shouldRefresh(
                FIRST_TURNTABLE, FIRST_TURNTABLE, 80, 116));
    }
}
