package com.zhongbai233.net_music_can_play_bili.blockentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TurntableComparatorSignalTest {
    @Test
    void mapsPlaybackProgressToComparatorSignal() {
        assertSignal(0, false, 0L, 60_000L);
        assertSignal(0, true, 0L, 0L);
        assertSignal(1, true, 0L, 60_000L);
        assertSignal(8, true, 30_000L, 60_000L);
        assertSignal(15, true, 60_000L, 60_000L);
        assertSignal(15, true, 90_000L, 60_000L);
        assertSignal(1, true, -1_000L, 60_000L);
    }

    private static void assertSignal(int expected, boolean hasDisc, long elapsedMillis, long durationMillis) {
        assertEquals(expected, TurntableComparatorSignal.fromProgress(hasDisc, elapsedMillis, durationMillis));
    }
}