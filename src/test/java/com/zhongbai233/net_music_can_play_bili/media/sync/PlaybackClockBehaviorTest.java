package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackClockBehaviorTest {
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

    @Test
    void compensatesAudioStartupLatency() {
        long capturedNanos = 10_000_000_000L;
        long nowNanos = capturedNanos + 17_431_999_999L;

        assertEquals(190_495L, AudioStartupSync.compensatedElapsedMillis(
                173_064L, 300_000L, capturedNanos, nowNanos));
        assertEquals(300_000L, AudioStartupSync.compensatedElapsedMillis(
                295_000L, 300_000L, capturedNanos, nowNanos));
        assertEquals(190_495L, AudioStartupSync.compensatedOffsetMillis(173_064L, 300_000L, 17_431L));
        assertEquals(0L, AudioStartupSync.elapsedSinceCaptureMillis(capturedNanos, capturedNanos - 1L));
    }

    @Test
    void clampsAsyncPrepareLatencyAndSaturatesOverflow() {
        assertEquals(25_000L, AudioStartupSync.compensatedOffsetMillis(20_000L, 60_000L, 5_000L));
        assertEquals(60_000L, AudioStartupSync.compensatedOffsetMillis(58_000L, 60_000L, 5_000L));
        assertEquals(5_000L, AudioStartupSync.compensatedOffsetMillis(-1L, 0L, 5_000L));
        assertEquals(Long.MAX_VALUE,
                AudioStartupSync.compensatedOffsetMillis(Long.MAX_VALUE - 10L, 0L, 50L));
    }

    @Test
    void smoothsVisualTimelineWithoutGoingBackwards() {
        VisualTimelineSmoother smoother = new VisualTimelineSmoother(500L, 20L, 0.20D);
        long start = 1_000_000_000L;
        assertEquals(1_000L, smoother.sample(1_000L, 10_000L, start));

        long advanced = smoother.sample(1_016L, 10_000L, start + 16_000_000L);
        assertTrue(advanced >= 1_016L && advanced <= 1_020L);
        long nonDecreasing = smoother.sample(980L, 10_000L, start + 32_000_000L);
        assertTrue(nonDecreasing >= advanced);
        assertEquals(nonDecreasing, smoother.sample(0L, 10_000L, start + 48_000_000L));
        assertEquals(2_000L, smoother.sample(2_000L, 10_000L, start + 64_000_000L));
        assertTrue(smoother.sample(12_000L, 10_000L, start + 80_000_000L) <= 10_000L);
    }
}
