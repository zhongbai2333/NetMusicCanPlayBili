package com.zhongbai233.net_music_can_play_bili.media.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MonotonicMediaClockTest {
    @Test
    void elapsedUsesOnlyMonotonicDelta() {
        long start = 8_000_000_000L;
        MonotonicMediaClock.Anchor anchor = MonotonicMediaClock.running(2_500L, start);

        assertEquals(4_000L, anchor.elapsedMillis(start + 1_500_000_000L, 10_000L));
    }

    @Test
    void backwardOrResetObservationCannotRewindProgress() {
        long start = 8_000_000_000L;
        MonotonicMediaClock.Anchor anchor = MonotonicMediaClock.running(2_500L, start);

        assertEquals(2_500L, anchor.elapsedMillis(1_000L, 10_000L));
    }

    @Test
    void largeBaseAndDeltaRemainPreciseAndClampToDuration() {
        MonotonicMediaClock.Anchor anchor = MonotonicMediaClock.running(299_999_995L, 3_000_000_000L);

        assertEquals(300_000_000L, anchor.elapsedMillis(Long.MAX_VALUE, 300_000_000L));
    }

    @Test
    void pauseAndResumePreserveElapsedPosition() {
        long start = 4_000_000_000L;
        MonotonicMediaClock.Anchor running = MonotonicMediaClock.running(1_000L, start);
        MonotonicMediaClock.Anchor paused = running.pause(start + 2_500_000_000L, 10_000L);
        MonotonicMediaClock.Anchor resumed = paused.resume(start + 9_000_000_000L, 10_000L);

        assertEquals(3_500L, paused.elapsedMillis(start + 8_000_000_000L, 10_000L));
        assertEquals(4_250L, resumed.elapsedMillis(start + 9_750_000_000L, 10_000L));
    }

    @Test
    void remainingSecondsRoundsUpWithoutGameTicks() {
        long start = 7_000_000_000L;
        MonotonicMediaClock.Anchor anchor = MonotonicMediaClock.running(8_001L, start);

        assertEquals(1L, MonotonicMediaClock.remainingSeconds(anchor, 10_000L, start + 1_100_000_000L));
    }
}
