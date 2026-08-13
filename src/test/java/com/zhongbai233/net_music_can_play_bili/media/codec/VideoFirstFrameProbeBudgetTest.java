package com.zhongbai233.net_music_can_play_bili.media.codec;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoFirstFrameProbeBudgetTest {
    @Test
    void staysWithinBudgetBeforeEitherBoundary() {
        assertEquals(VideoFirstFrameProbeBudget.Outcome.WITHIN_BUDGET,
                VideoFirstFrameProbeBudget.evaluate(
                        TimeUnit.MILLISECONDS.toNanos(1_999L), 2_000L, 179, 180));
    }

    @Test
    void timeBoundaryIsInclusive() {
        assertEquals(VideoFirstFrameProbeBudget.Outcome.TIME_EXHAUSTED,
                VideoFirstFrameProbeBudget.evaluate(
                        TimeUnit.MILLISECONDS.toNanos(2_000L), 2_000L, 1, 180));
    }

    @Test
    void packetBoundaryIsInclusive() {
        assertEquals(VideoFirstFrameProbeBudget.Outcome.PACKET_EXHAUSTED,
                VideoFirstFrameProbeBudget.evaluate(
                        TimeUnit.MILLISECONDS.toNanos(100L), 2_000L, 180, 180));
    }

    @Test
    void firstFrameProducedByTheLastAllowedPacketWinsTheRace() {
        assertEquals(VideoFirstFrameProbeBudget.Outcome.WITHIN_BUDGET,
                VideoFirstFrameProbeBudget.evaluate(true,
                        TimeUnit.MILLISECONDS.toNanos(100L), 2_000L, 256, 256));
    }

    @Test
    void frameProducedAtTheTimeDeadlineIsTooLate() {
        assertEquals(VideoFirstFrameProbeBudget.Outcome.TIME_EXHAUSTED,
                VideoFirstFrameProbeBudget.evaluate(true,
                        TimeUnit.MILLISECONDS.toNanos(2_000L), 2_000L, 1, 256));
    }

    @Test
    void elapsedTimeWinsWhenBothBoundariesAreReached() {
        assertEquals(VideoFirstFrameProbeBudget.Outcome.TIME_EXHAUSTED,
                VideoFirstFrameProbeBudget.evaluate(
                        TimeUnit.MILLISECONDS.toNanos(2_000L), 2_000L, 180, 180));
    }

    @Test
    void nonPositiveLimitsDisableTheirRespectiveBudget() {
        assertEquals(VideoFirstFrameProbeBudget.Outcome.WITHIN_BUDGET,
                VideoFirstFrameProbeBudget.evaluate(Long.MAX_VALUE, 0L, Integer.MAX_VALUE, 0));
    }
}
