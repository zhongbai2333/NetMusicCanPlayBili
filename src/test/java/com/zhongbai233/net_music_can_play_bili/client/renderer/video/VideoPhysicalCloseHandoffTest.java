package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoPhysicalCloseHandoffTest {
    @Test
    void exposesTheSameFourPendingFuturesFromBirth() {
        VideoPhysicalCloseHandoff collector = new VideoPhysicalCloseHandoff();

        ProjectionReplacementGate.CloseHandoff first = collector.snapshot();
        ProjectionReplacementGate.CloseHandoff second = collector.snapshot();

        assertSame(first, second);
        assertSame(first.closeReturned(), second.closeReturned());
        assertSame(first.nativeTermination(), second.nativeTermination());
        assertSame(first.decodeExit(), second.decodeExit());
        assertSame(first.renderRelease(), second.renderRelease());
        assertFalse(first.closeReturned().isDone());
        assertFalse(first.nativeTermination().isDone());
        assertFalse(first.decodeExit().isDone());
        assertFalse(first.renderRelease().isDone());
    }

    @Test
    void emptyInstanceCompletesNormallyWhenSealed() {
        VideoPhysicalCloseHandoff collector = new VideoPhysicalCloseHandoff();

        collector.seal(CompletableFuture.completedFuture(null));

        joinAll(collector.snapshot());
    }

    @Test
    void stopDuringOpenAcceptsLatePhysicalSignalsUntilDecodeExit() {
        VideoPhysicalCloseHandoff collector = new VideoPhysicalCloseHandoff();
        CompletableFuture<Void> close = new CompletableFuture<>();
        CompletableFuture<Void> termination = new CompletableFuture<>();
        CompletableFuture<Void> exit = new CompletableFuture<>();
        collector.beginDecode(exit);

        ProjectionReplacementGate.CloseHandoff handoff = collector.snapshot();
        collector.seal(CompletableFuture.completedFuture(null));
        assertTrue(handoff.renderRelease().isDone());
        assertFalse(handoff.closeReturned().isDone());
        assertFalse(handoff.nativeTermination().isDone());
        assertFalse(handoff.decodeExit().isDone());

        collector.attachDecoder(termination);
        collector.attachClose(close, termination, exit);
        exit.complete(null);

        assertTrue(handoff.decodeExit().isDone());
        assertFalse(handoff.closeReturned().isDone());
        assertFalse(handoff.nativeTermination().isDone());

        close.complete(null);
        assertTrue(handoff.closeReturned().isDone());
        assertFalse(handoff.nativeTermination().isDone());

        termination.complete(null);
        joinAll(handoff);
    }

    @Test
    void attachClosePublishesAllSignalsBeforeAnAlreadyCompletedExitCanSeal() {
        VideoPhysicalCloseHandoff collector = new VideoPhysicalCloseHandoff();
        CompletableFuture<Void> close = new CompletableFuture<>();
        CompletableFuture<Void> termination = new CompletableFuture<>();
        CompletableFuture<Void> exit = CompletableFuture.completedFuture(null);

        collector.attachClose(close, termination, exit);
        collector.seal(CompletableFuture.completedFuture(null));

        ProjectionReplacementGate.CloseHandoff handoff = collector.snapshot();
        assertTrue(handoff.decodeExit().isDone());
        assertFalse(handoff.closeReturned().isDone());
        assertFalse(handoff.nativeTermination().isDone());
        close.complete(null);
        termination.complete(null);
        joinAll(handoff);
    }

    @Test
    void multipleDecodeGenerationsAndRestartsAreAggregated() {
        VideoPhysicalCloseHandoff collector = new VideoPhysicalCloseHandoff();
        Generation first = new Generation();
        Generation second = new Generation();
        collector.beginDecode(first.exit);
        collector.attachDecoder(first.termination);
        collector.attachClose(first.close, first.termination, first.exit);
        first.completeAll();

        collector.beginDecode(second.exit);
        collector.attachDecoder(second.termination);
        collector.attachClose(second.close, second.termination, second.exit);
        collector.seal(CompletableFuture.completedFuture(null));

        ProjectionReplacementGate.CloseHandoff handoff = collector.snapshot();
        assertFalse(handoff.closeReturned().isDone());
        assertFalse(handoff.nativeTermination().isDone());
        assertFalse(handoff.decodeExit().isDone());
        second.close.complete(null);
        second.termination.complete(null);
        assertFalse(handoff.closeReturned().isDone());
        assertFalse(handoff.nativeTermination().isDone());

        second.exit.complete(null);
        joinAll(handoff);
    }

    @Test
    void exceptionalAndCancelledSignalsFailTheStableHandoffClosed() {
        VideoPhysicalCloseHandoff collector = new VideoPhysicalCloseHandoff();
        CompletableFuture<Void> failedExit = new CompletableFuture<>();
        CompletableFuture<Void> pendingExit = new CompletableFuture<>();
        CompletableFuture<Void> failedClose = new CompletableFuture<>();
        CompletableFuture<Void> cancelledTermination = new CompletableFuture<>();
        collector.beginDecode(failedExit);
        collector.beginDecode(pendingExit);
        collector.attachClose(failedClose, cancelledTermination, failedExit);
        collector.seal(CompletableFuture.completedFuture(null));

        failedClose.completeExceptionally(new IllegalStateException("close failed"));
        cancelledTermination.cancel(false);
        failedExit.completeExceptionally(new IllegalStateException("decode failed"));

        ProjectionReplacementGate.CloseHandoff handoff = collector.snapshot();
        assertTrue(handoff.closeReturned().isCompletedExceptionally());
        assertTrue(handoff.nativeTermination().isCompletedExceptionally());
        assertTrue(handoff.decodeExit().isCompletedExceptionally());
        assertFalse(pendingExit.isDone());

        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>();
        ProjectionReplacementGate.Intent<String> intent = gate.beginIntent("projector", "replacement", handoff);
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, gate.evaluate(intent));

        pendingExit.complete(null);
        assertThrows(IllegalStateException.class,
                () -> collector.attachDecoder(CompletableFuture.completedFuture(null)));
    }

    @Test
    void exceptionalRenderReleaseFailsClosedWhileOtherEmptySignalsConverge() {
        VideoPhysicalCloseHandoff collector = new VideoPhysicalCloseHandoff();
        CompletableFuture<Void> release = new CompletableFuture<>();

        collector.seal(release);
        release.completeExceptionally(new IllegalStateException("render release failed"));

        ProjectionReplacementGate.CloseHandoff handoff = collector.snapshot();
        handoff.closeReturned().join();
        handoff.nativeTermination().join();
        handoff.decodeExit().join();
        assertTrue(handoff.renderRelease().isCompletedExceptionally());
    }

    @Test
    void sealRejectsNewDecodeButAllowsSignalsForAnAlreadyRegisteredDecode() {
        VideoPhysicalCloseHandoff collector = new VideoPhysicalCloseHandoff();
        CompletableFuture<Void> registeredExit = new CompletableFuture<>();
        collector.beginDecode(registeredExit);
        collector.seal(CompletableFuture.completedFuture(null));

        assertThrows(IllegalStateException.class,
                () -> collector.beginDecode(new CompletableFuture<>()));
        assertThrows(IllegalStateException.class,
                () -> collector.attachClose(new CompletableFuture<>(), new CompletableFuture<>(),
                        new CompletableFuture<>()));

        CompletableFuture<Void> close = new CompletableFuture<>();
        CompletableFuture<Void> termination = new CompletableFuture<>();
        collector.attachClose(close, termination, registeredExit);
        close.complete(null);
        termination.complete(null);
        registeredExit.complete(null);
        joinAll(collector.snapshot());
    }

    @Test
    void registrationAfterAtomicClosureIsRejected() {
        VideoPhysicalCloseHandoff collector = new VideoPhysicalCloseHandoff();
        collector.seal(CompletableFuture.completedFuture(null));

        assertThrows(IllegalStateException.class,
                () -> collector.attachDecoder(CompletableFuture.completedFuture(null)));
        assertThrows(IllegalStateException.class,
                () -> collector.attachClose(CompletableFuture.completedFuture(null),
                        CompletableFuture.completedFuture(null), CompletableFuture.completedFuture(null)));
    }

    @Test
    void repeatedSealIsIdempotentOnlyForTheSameRenderSignal() {
        VideoPhysicalCloseHandoff collector = new VideoPhysicalCloseHandoff();
        CompletableFuture<Void> release = new CompletableFuture<>();
        collector.seal(release);

        collector.seal(release);
        assertThrows(IllegalStateException.class,
                () -> collector.seal(CompletableFuture.completedFuture(null)));
        release.complete(null);
        joinAll(collector.snapshot());
    }

    private static void joinAll(ProjectionReplacementGate.CloseHandoff handoff) {
        handoff.closeReturned().join();
        handoff.nativeTermination().join();
        handoff.decodeExit().join();
        handoff.renderRelease().join();
    }

    private static final class Generation {
        private final CompletableFuture<Void> close = new CompletableFuture<>();
        private final CompletableFuture<Void> termination = new CompletableFuture<>();
        private final CompletableFuture<Void> exit = new CompletableFuture<>();

        private void completeAll() {
            close.complete(null);
            termination.complete(null);
            exit.complete(null);
        }
    }
}
