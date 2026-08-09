package com.zhongbai233.net_music_can_play_bili.client.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaTimelineClockTest {
    @Test
    void absorbsSameGameTimeObservationOnlyOnce() {
        assertTrue(MediaTimelineClock.isNewObservation(42L, 1_100L, 60_000L,
            Long.MIN_VALUE, 0L, 0L));
        assertFalse(MediaTimelineClock.isNewObservation(42L, 1_100L, 60_000L,
            42L, 1_100L, 60_000L));
        assertTrue(MediaTimelineClock.isNewObservation(43L, 1_100L, 60_000L,
            42L, 1_100L, 60_000L));
    }
}