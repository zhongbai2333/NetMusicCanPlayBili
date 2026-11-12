package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoCloseDiagnosticsTest {

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
