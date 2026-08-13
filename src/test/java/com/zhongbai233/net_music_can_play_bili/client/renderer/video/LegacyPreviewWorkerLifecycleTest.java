package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPreviewWorkerLifecycleTest {
    @Test
    void onlyOneGenerationCanOwnTheWorkerAtATime() {
        LegacyPreviewWorkerLifecycle<String, String> lifecycle = new LegacyPreviewWorkerLifecycle<>();
        long generation = lifecycle.tryBegin();

        assertTrue(generation >= 0L);
        assertEquals(LegacyPreviewWorkerLifecycle.REJECTED_GENERATION, lifecycle.tryBegin());
        assertTrue(lifecycle.bindWorker(generation, "worker"));
        assertFalse(lifecycle.bindWorker(generation, "replacement-worker"));
        assertSame("worker", lifecycle.snapshot().worker());
    }

    @Test
    void stopAtomicallyDetachesWorkerAndDecoderAndInvalidatesGeneration() {
        LegacyPreviewWorkerLifecycle<String, String> lifecycle = new LegacyPreviewWorkerLifecycle<>();
        long generation = lifecycle.tryBegin();
        lifecycle.bindWorker(generation, "worker");
        lifecycle.bindDecoder(generation, "decoder");

        LegacyPreviewWorkerLifecycle.Detached<String, String> detached = lifecycle.stopAndDetach();

        assertEquals(generation, detached.generation());
        assertSame("worker", detached.worker());
        assertSame("decoder", detached.decoder());
        assertFalse(lifecycle.isRunning());
        assertFalse(lifecycle.isStarted());
        assertNull(lifecycle.snapshot().worker());
        assertNull(lifecycle.snapshot().decoder());
    }

    @Test
    void lateBindingsAreRejectedAfterStop() {
        LegacyPreviewWorkerLifecycle<String, String> lifecycle = new LegacyPreviewWorkerLifecycle<>();
        long generation = lifecycle.tryBegin();
        lifecycle.stopAndDetach();

        assertFalse(lifecycle.bindWorker(generation, "late-worker"));
        assertFalse(lifecycle.bindDecoder(generation, "late-decoder"));
    }

    @Test
    void staleCompletionCannotStopAReplacementGeneration() {
        LegacyPreviewWorkerLifecycle<String, String> lifecycle = new LegacyPreviewWorkerLifecycle<>();
        long firstGeneration = lifecycle.tryBegin();
        lifecycle.bindDecoder(firstGeneration, "first-decoder");
        lifecycle.stopAndDetach();
        long replacementGeneration = lifecycle.tryBegin();
        lifecycle.bindWorker(replacementGeneration, "replacement-worker");
        lifecycle.bindDecoder(replacementGeneration, "replacement-decoder");

        assertFalse(lifecycle.finish(firstGeneration, "first-decoder"));
        assertTrue(lifecycle.isActive(replacementGeneration));
        assertSame("replacement-worker", lifecycle.snapshot().worker());
        assertSame("replacement-decoder", lifecycle.snapshot().decoder());
    }

    @Test
    void cooperativeStopConvergesOnlyWhenCurrentWorkerFinishes() {
        LegacyPreviewWorkerLifecycle<String, String> lifecycle = new LegacyPreviewWorkerLifecycle<>();
        long generation = lifecycle.tryBegin();
        lifecycle.bindWorker(generation, "worker");
        lifecycle.requestStop();

        assertFalse(lifecycle.isRunning());
        assertTrue(lifecycle.isStarted());
        assertTrue(lifecycle.finish(generation, null));
        assertFalse(lifecycle.isStarted());
        assertNull(lifecycle.snapshot().worker());
    }
}
