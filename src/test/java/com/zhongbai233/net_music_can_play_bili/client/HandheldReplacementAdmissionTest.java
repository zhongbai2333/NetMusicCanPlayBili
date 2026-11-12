package com.zhongbai233.net_music_can_play_bili.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandheldReplacementAdmissionTest {
    @Test
    void stableSignalsRemainPendingAcrossBusinessStateReplacement() {
        HandheldReplacementGate gate = new HandheldReplacementGate();
        CompletableFuture<Void> decodeExit = new CompletableFuture<>();
        CompletableFuture<Void> nativeTermination = new CompletableFuture<>();
        gate.install("old-session", decodeExit, nativeTermination);

        HandheldReplacementGate.Signals beforeClear = gate.snapshot();
        HandheldReplacementGate.Signals afterRecreate = gate.snapshot();
        assertSame(beforeClear, afterRecreate);
        assertFalse(gate.completedNormally());

        decodeExit.complete(null);
        assertFalse(gate.completedNormally());
        nativeTermination.complete(null);
        assertTrue(gate.completedNormally());
    }

    @Test
    void timeoutWinnerCannotBeRevivedByLatePhysicalCompletion() {
        HandheldReplacementWait wait = new HandheldReplacementWait();

        assertEquals(HandheldReplacementWait.Outcome.FAIL_CLOSED,
                wait.complete(new TimeoutException(), HandheldDecoderAdmissionPolicy.Decision.WAIT));
        assertEquals(HandheldReplacementWait.Outcome.ALREADY_DECIDED,
                wait.complete(null, HandheldDecoderAdmissionPolicy.Decision.OPEN));
    }

    @Test
    void normalCompletionOpensExactlyOnce() {
        HandheldReplacementWait wait = new HandheldReplacementWait();

        assertEquals(HandheldReplacementWait.Outcome.OPEN,
                wait.complete(null, HandheldDecoderAdmissionPolicy.Decision.OPEN));
        assertEquals(HandheldReplacementWait.Outcome.ALREADY_DECIDED,
                wait.complete(null, HandheldDecoderAdmissionPolicy.Decision.OPEN));
    }

    @Test
    void opensOnlyAfterDecodeAndNativeTerminationBothCompleteNormally() {
        CompletableFuture<Void> decodeExit = new CompletableFuture<>();
        CompletableFuture<Void> nativeTermination = new CompletableFuture<>();

        assertEquals(HandheldDecoderAdmissionPolicy.Decision.WAIT,
                HandheldDecoderAdmissionPolicy.decide(decodeExit, nativeTermination));
        decodeExit.complete(null);
        assertEquals(HandheldDecoderAdmissionPolicy.Decision.WAIT,
                HandheldDecoderAdmissionPolicy.decide(decodeExit, nativeTermination));
        nativeTermination.complete(null);
        assertEquals(HandheldDecoderAdmissionPolicy.Decision.OPEN,
                HandheldDecoderAdmissionPolicy.decide(decodeExit, nativeTermination));
    }

    @Test
    void exceptionalOrCancelledSignalFailsClosed() {
        CompletableFuture<Void> failedExit = new CompletableFuture<>();
        failedExit.completeExceptionally(new IllegalStateException("decode exit failed"));
        assertEquals(HandheldDecoderAdmissionPolicy.Decision.FAIL_CLOSED,
                HandheldDecoderAdmissionPolicy.decide(failedExit, CompletableFuture.completedFuture(null)));

        CompletableFuture<Void> cancelledTermination = new CompletableFuture<>();
        cancelledTermination.cancel(false);
        assertEquals(HandheldDecoderAdmissionPolicy.Decision.FAIL_CLOSED,
                HandheldDecoderAdmissionPolicy.decide(CompletableFuture.completedFuture(null),
                        cancelledTermination));
        assertEquals(HandheldDecoderAdmissionPolicy.Decision.FAIL_CLOSED,
                HandheldDecoderAdmissionPolicy.decide(null, CompletableFuture.completedFuture(null)));
        assertEquals(HandheldDecoderAdmissionPolicy.Decision.FAIL_CLOSED,
                HandheldDecoderAdmissionPolicy.decide(CompletableFuture.completedFuture(null), null));
    }

    @Test
    void convergenceDoesNotCompleteAfterOnlyOnePhysicalSignal() {
        CompletableFuture<Void> decodeExit = new CompletableFuture<>();
        CompletableFuture<Void> nativeTermination = new CompletableFuture<>();
        CompletableFuture<Void> convergence = HandheldDecoderAdmissionPolicy.convergence(
                decodeExit, nativeTermination);

        decodeExit.complete(null);
        assertFalse(convergence.isDone());
        nativeTermination.complete(null);
        assertTrue(HandheldDecoderAdmissionPolicy.completedNormally(convergence));
    }
}
