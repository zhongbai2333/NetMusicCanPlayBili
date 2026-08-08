package com.zhongbai233.net_music_can_play_bili.media.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioNativeCloseDiagnosticsTest {
    @Test
    void deferredDeleteRemainsActiveUntilNativeLoopReturns() {
        AudioNativeCloseDiagnostics diagnostics = new AudioNativeCloseDiagnostics(8, 8, 100L, 200L);
        long operation = diagnostics.begin(2, 96, 1_000L);
        diagnostics.deferred(operation);

        AudioNativeCloseDiagnostics.Snapshot pending = diagnostics.snapshot(1_050L);
        assertEquals(1, pending.activeOperations());
        assertEquals(1, pending.deferredOperations());
        assertEquals(2L, pending.totalSourcesRequested());
        assertEquals(96L, pending.totalBuffersRequested());

        diagnostics.complete(operation, 1_080L);
        assertEquals(0, diagnostics.snapshot(1_080L).activeOperations());
    }

    @Test
    void timeoutIsReportedOnceAndLateDeleteConverges() {
        AudioNativeCloseDiagnostics diagnostics = new AudioNativeCloseDiagnostics(8, 8, 100L, 200L);
        long operation = diagnostics.begin(1, 48, 1_000L);

        assertEquals(1, diagnostics.tick(1_100L).size());
        assertEquals(1, diagnostics.tick(1_200L).size());
        assertTrue(diagnostics.tick(1_300L).isEmpty());
        diagnostics.complete(operation, 1_350L);

        AudioNativeCloseDiagnostics.Snapshot snapshot = diagnostics.snapshot(1_350L);
        assertEquals(1L, snapshot.softTimeouts());
        assertEquals(1L, snapshot.hardTimeouts());
        assertEquals(1L, snapshot.lateConvergences());
    }

    @Test
    void completionAndCapacityAreIdempotentAndBounded() {
        AudioNativeCloseDiagnostics diagnostics = new AudioNativeCloseDiagnostics(2, 1, 100L, 200L);
        long first = diagnostics.begin(1, 1, 1L);
        diagnostics.begin(1, 1, 2L);
        long third = diagnostics.begin(1, 1, 3L);
        diagnostics.complete(first, 4L);
        diagnostics.complete(third, 5L);
        diagnostics.complete(third, 6L);

        AudioNativeCloseDiagnostics.Snapshot snapshot = diagnostics.snapshot(6L);
        assertEquals(1, snapshot.activeOperations());
        assertEquals(1, snapshot.retainedCompleted());
        assertEquals(1L, snapshot.droppedOperations());
    }
}