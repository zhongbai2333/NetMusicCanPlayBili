package com.zhongbai233.net_music_can_play_bili.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandheldDecoderAdmissionPolicyTest {
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
