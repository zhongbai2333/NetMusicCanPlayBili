package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualTimelineSmootherTest {
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