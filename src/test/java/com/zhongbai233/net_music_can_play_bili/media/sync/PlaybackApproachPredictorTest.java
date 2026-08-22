package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackApproachPredictorTest {
    @Test
    void predictsOnlyAForwardBoundedApproach() {
        assertTrue(PlaybackApproachPredictor.willEnterSphere(
                100, 0, 0, -1, 0, 0, 64, 0, 0, 8));
        assertFalse(PlaybackApproachPredictor.willEnterSphere(
                100, 0, 0, 1, 0, 0, 64, 0, 0, 8));
        assertFalse(PlaybackApproachPredictor.willEnterSphere(
                200, 0, 0, -1, 0, 0, 64, 0, 0, 8));
        assertFalse(PlaybackApproachPredictor.willEnterSphere(
                70, 0, 0, -1, 0, 0, 64, 0, 0, 8));
    }

    @Test
    void segmentIntersectionCatchesFastAabbPassThrough() {
        assertTrue(PlaybackApproachPredictor.willEnterAabb(
                30, 0, 0, -2, 0, 0, 0, 0, 0, 4, 4, 4));
        assertFalse(PlaybackApproachPredictor.willEnterAabb(
                30, 0, 0, 2, 0, 0, 0, 0, 0, 4, 4, 4));
    }
}
