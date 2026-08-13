package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoCloseDiagnosticsTest {
    @Test
    void phasesMayCompleteOutOfOrderAndRepeatedEventsAreIdempotent() {
        VideoCloseDiagnostics diagnostics = new VideoCloseDiagnostics(8, 8, 100L, 200L);
        long operation = diagnostics.begin("session", EnumSet.of(
                VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED,
                VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED,
                VideoCloseDiagnostics.Phase.RENDER_RELEASE_RETURNED), 1_000L);

        diagnostics.complete(operation, VideoCloseDiagnostics.Phase.RENDER_RELEASE_RETURNED, 1_010L);
        diagnostics.complete(operation, VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED, 1_020L);
        diagnostics.complete(operation, VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED, 1_030L);
        assertEquals(1, diagnostics.snapshot(1_030L).activeOperations());
        diagnostics.complete(operation, VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED, 1_040L);

        VideoCloseDiagnostics.Snapshot snapshot = diagnostics.snapshot(1_040L);
        assertEquals(0, snapshot.activeOperations());
        assertEquals(1, snapshot.retainedCompleted());
        assertEquals(40L, snapshot.latestConvergenceNanos());
        assertEquals("session", snapshot.latestSessionId());
    }

    @Test
    void softAndHardTimeoutsFireOnceAndLateCompletionIsRecorded() {
        VideoCloseDiagnostics diagnostics = new VideoCloseDiagnostics(8, 8, 100L, 200L);
        long operation = diagnostics.begin("session", EnumSet.of(
                VideoCloseDiagnostics.Phase.NATIVE_TERMINATED), 1_000L);

        assertEquals(1, diagnostics.tick(1_100L).size());
        assertEquals(1, diagnostics.tick(1_200L).size());
        assertTrue(diagnostics.tick(1_300L).isEmpty());
        diagnostics.complete(operation, VideoCloseDiagnostics.Phase.NATIVE_TERMINATED, 1_350L);

        VideoCloseDiagnostics.Snapshot snapshot = diagnostics.snapshot(1_350L);
        assertEquals(0, snapshot.activeOperations());
        assertEquals(1L, snapshot.softTimeouts());
        assertEquals(1L, snapshot.hardTimeouts());
        assertEquals(1L, snapshot.lateConvergences());
    }

    @Test
    void activeCapacityIsBoundedWithoutHoldingOldOperationsForever() {
        VideoCloseDiagnostics diagnostics = new VideoCloseDiagnostics(2, 2, 100L, 200L);
        for (int i = 0; i < 3; i++) {
            diagnostics.begin("session-" + i, EnumSet.of(VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED), i);
        }
        VideoCloseDiagnostics.Snapshot snapshot = diagnostics.snapshot(3L);
        assertEquals(2, snapshot.activeOperations());
        assertEquals(1L, snapshot.droppedOperations());
    }

    @Test
    void onlyDeclaredPhasesAreRequiredAndEmptyOperationConvergesImmediately() {
        VideoCloseDiagnostics diagnostics = new VideoCloseDiagnostics(8, 8, 100L, 200L);
        long operation = diagnostics.begin("non-native", EnumSet.of(
                VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED), 1_000L);

        diagnostics.complete(operation, VideoCloseDiagnostics.Phase.NATIVE_TERMINATED, 1_010L);
        assertEquals(1, diagnostics.snapshot(1_010L).activeOperations());
        diagnostics.complete(operation, VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED, 1_020L);
        diagnostics.begin("already-finished", EnumSet.noneOf(VideoCloseDiagnostics.Phase.class), 1_030L);

        VideoCloseDiagnostics.Snapshot snapshot = diagnostics.snapshot(1_030L);
        assertEquals(0, snapshot.activeOperations());
        assertEquals(2, snapshot.retainedCompleted());
    }

    @Test
    void typedSessionRemainsInternalAndLegacyInvalidValueBecomesAbsent() {
        VideoCloseDiagnostics diagnostics = new VideoCloseDiagnostics(8, 8, 100L, 200L);
        diagnostics.begin(PlaybackSessionId.of("typed-session"), EnumSet.noneOf(VideoCloseDiagnostics.Phase.class),
                1_000L);
        assertEquals("typed-session", diagnostics.snapshot(1_000L).latestSessionId());

        diagnostics.begin("not a valid session", EnumSet.noneOf(VideoCloseDiagnostics.Phase.class), 1_010L);
        assertEquals("<none>", diagnostics.snapshot(1_010L).latestSessionId());
    }

    @Test
    void exceptionalPhaseConvergesButRetainsFailureEvidence() {
        VideoCloseDiagnostics diagnostics = new VideoCloseDiagnostics(8, 8, 100L, 200L);
        long operation = diagnostics.begin("session", EnumSet.of(
                VideoCloseDiagnostics.Phase.NATIVE_TERMINATED), 1_000L);

        diagnostics.complete(operation, VideoCloseDiagnostics.Phase.NATIVE_TERMINATED,
                new IllegalStateException("native close failed"), 1_020L);

        VideoCloseDiagnostics.Snapshot snapshot = diagnostics.snapshot(1_020L);
        assertEquals(0, snapshot.activeOperations());
        assertEquals(1L, snapshot.failedConvergences());
        assertTrue(snapshot.latestFailure().contains("NATIVE_TERMINATED"));
        assertTrue(snapshot.latestFailure().contains("native close failed"));
    }

    @Test
    void activeDescriptionReportsExactPendingPhasesWithoutHoldingResources() {
        VideoCloseDiagnostics diagnostics = new VideoCloseDiagnostics(8, 8, 100L, 200L);
        long operation = diagnostics.begin("session", EnumSet.of(
                VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED,
                VideoCloseDiagnostics.Phase.NATIVE_TERMINATED), 1_000L);
        diagnostics.complete(operation, VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED, 1_010L);

        String description = diagnostics.activeDescriptions(1_020L).getFirst();
        assertTrue(description.contains("session=session"));
        assertTrue(description.contains("pending=[NATIVE_TERMINATED]"));
        assertTrue(description.contains("ageMs=0"));
    }

    @Test
    void alreadyCompletedFutureObservedAfterBeginCannotStrandRequiredPhase() {
        VideoCloseDiagnostics diagnostics = new VideoCloseDiagnostics(8, 8, 100L, 200L);
        CompletableFuture<Void> decodeExit = new CompletableFuture<>();
        long operation = diagnostics.begin("session", EnumSet.of(
                VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED), 1_000L);

        // Reproduces the stop() race: the phase was declared pending, then the worker exited before the callback
        // could be registered. whenComplete must still converge the operation immediately.
        decodeExit.complete(null);
        diagnostics.observe(operation, VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED, decodeExit);

        VideoCloseDiagnostics.Snapshot snapshot = diagnostics.snapshot(System.nanoTime());
        assertEquals(0, snapshot.activeOperations());
        assertEquals(1, snapshot.retainedCompleted());
    }
}
