package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionReplacementGateTest {
    @Test
    void everyPhysicalCloseSignalMustCompleteNormallyBeforeOpen() {
        ManualScheduler scheduler = new ManualScheduler();
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(scheduler);
        Signals signals = new Signals();
        ProjectionReplacementGate.Intent<String> intent = gate.beginIntent("projector", "session-b",
                signals.handoff());

        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(intent));
        CompletableFuture<ProjectionReplacementGate.Decision> outcome = gate.waitFor(intent, 3L, TimeUnit.SECONDS);

        signals.closeReturned.complete(null);
        signals.nativeTermination.complete(null);
        signals.decodeExit.complete(null);
        assertFalse(outcome.isDone());
        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(intent));

        signals.renderRelease.complete(null);
        assertEquals(ProjectionReplacementGate.Decision.OPEN, outcome.join());
        assertEquals(ProjectionReplacementGate.Decision.OPEN, gate.evaluate(intent));

        AtomicInteger starts = new AtomicInteger();
        assertTrue(gate.commitIfOpen(intent, starts::incrementAndGet));
        assertEquals(1, starts.get());
        assertFalse(gate.isCurrent(intent));
        assertFalse(gate.commitIfOpen(intent, starts::incrementAndGet));
        assertEquals(1, starts.get());
    }

    @Test
    void exceptionalAndCancelledSignalsFailClosedWithoutWaitingForTheOthers() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        Signals exceptional = new Signals();
        exceptional.closeReturned.completeExceptionally(new IllegalStateException("close failed"));
        ProjectionReplacementGate.Intent<String> first = gate.beginIntent("first", "session",
                exceptional.handoff());

        Signals cancelled = new Signals();
        cancelled.nativeTermination.cancel(false);
        ProjectionReplacementGate.Intent<String> second = gate.beginIntent("second", "session",
                cancelled.handoff());

        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, gate.evaluate(first));
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED,
                gate.waitFor(first, 3L, TimeUnit.SECONDS).join());
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, gate.evaluate(second));
        assertFalse(gate.commitIfOpen(first, () -> {
            throw new AssertionError("must not open");
        }));
        assertFalse(gate.commitIfOpen(second, () -> {
            throw new AssertionError("must not open");
        }));
    }

    @Test
    void timeoutWinnerCannotBeUndoneOrRearmedByLateCompletion() {
        ManualScheduler scheduler = new ManualScheduler();
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(scheduler);
        Signals signals = new Signals();
        ProjectionReplacementGate.Intent<String> intent = gate.beginIntent("projector", "session",
                signals.handoff());
        CompletableFuture<ProjectionReplacementGate.Decision> firstWait = gate.waitFor(intent, 3L,
                TimeUnit.SECONDS);

        assertSame(firstWait, gate.waitFor(intent, 30L, TimeUnit.SECONDS));
        scheduler.runNext();
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, firstWait.join());

        signals.completeAll();
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, firstWait.join());
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, gate.evaluate(intent));
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED,
                gate.waitFor(intent, 3L, TimeUnit.SECONDS).join());
        assertFalse(gate.commitIfOpen(intent, () -> {
            throw new AssertionError("late completion must not open the timed-out intent");
        }));
    }

    @Test
    void normalCompletionWinnerCannotBeReversedByTheScheduledTimeout() {
        ManualScheduler scheduler = new ManualScheduler();
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(scheduler);
        Signals signals = new Signals();
        ProjectionReplacementGate.Intent<String> intent = gate.beginIntent("projector", "session",
                signals.handoff());
        CompletableFuture<ProjectionReplacementGate.Decision> outcome = gate.waitFor(intent, 3L, TimeUnit.SECONDS);

        signals.completeAll();
        assertEquals(ProjectionReplacementGate.Decision.OPEN, outcome.join());
        scheduler.runNext();

        assertEquals(ProjectionReplacementGate.Decision.OPEN, outcome.join());
        assertTrue(gate.commitIfOpen(intent, () -> {
        }));
    }

    @Test
    void newerIntentSupersedesTheOldWaiterAndOnlyItMayCommit() {
        ManualScheduler scheduler = new ManualScheduler();
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(scheduler);
        Signals oldOwner = new Signals();
        ProjectionReplacementGate.Intent<String> stale = gate.beginIntent("projector", "session-old",
                oldOwner.handoff());
        CompletableFuture<ProjectionReplacementGate.Decision> staleOutcome = gate.waitFor(stale, 3L,
                TimeUnit.SECONDS);

        ProjectionReplacementGate.Intent<String> current = gate.beginIntent("projector", "session-new",
                ProjectionReplacementGate.CloseHandoff.completed());

        assertTrue(current.epoch() > stale.epoch());
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, staleOutcome.join());
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, gate.evaluate(stale));
        // The pending old physical handoff cannot be overwritten by a completed placeholder.
        assertSame(oldOwner.handoff(), current.closeHandoff());
        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(current));

        oldOwner.completeAll();
        assertEquals(ProjectionReplacementGate.Decision.OPEN,
                gate.waitFor(current, 3L, TimeUnit.SECONDS).join());
        AtomicInteger starts = new AtomicInteger();
        assertFalse(gate.commitIfOpen(stale, starts::incrementAndGet));
        assertTrue(gate.commitIfOpen(current, starts::incrementAndGet));
        assertEquals(1, starts.get());
    }

    @Test
    void resolvedEligibilityStillRequiresACommitTimeEpochCheck() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        ProjectionReplacementGate.Intent<String> stale = gate.beginIntent("projector", "session-old",
                ProjectionReplacementGate.CloseHandoff.completed());
        assertEquals(ProjectionReplacementGate.Decision.OPEN,
                gate.waitFor(stale, 3L, TimeUnit.SECONDS).join());

        ProjectionReplacementGate.Intent<String> current = gate.beginIntent("projector", "session-new",
                ProjectionReplacementGate.CloseHandoff.completed());

        AtomicInteger starts = new AtomicInteger();
        assertFalse(gate.commitIfOpen(stale, starts::incrementAndGet));
        assertTrue(gate.commitIfOpen(current, starts::incrementAndGet));
        assertEquals(1, starts.get());
    }

    @Test
    void retainedHandoffSurvivesLogicalRegistryClearOrRemove() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        Signals detached = new Signals();
        gate.retainCloseHandoff("projector", detached.handoff());

        ProjectionReplacementGate.Intent<String> replacement = gate.beginIntent("projector", "session-new",
                ProjectionReplacementGate.CloseHandoff.completed());

        assertSame(detached.handoff(), replacement.closeHandoff());
        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(replacement));
        detached.completeAll();
        assertEquals(ProjectionReplacementGate.Decision.OPEN, gate.evaluate(replacement));
    }

    @Test
    void logicalRemovalInvalidatesAnAlreadyWaitingIntentButKeepsItsSignals() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        Signals detached = new Signals();
        ProjectionReplacementGate.Intent<String> removed = gate.beginIntent("projector", "session",
                detached.handoff());
        CompletableFuture<ProjectionReplacementGate.Decision> removedOutcome = gate.waitFor(removed, 3L,
                TimeUnit.SECONDS);

        gate.retainCloseHandoff("projector", detached.handoff());

        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, removedOutcome.join());
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, gate.evaluate(removed));
        ProjectionReplacementGate.Intent<String> replacement = gate.beginIntent("projector", "session-next",
                ProjectionReplacementGate.CloseHandoff.completed());
        assertSame(detached.handoff(), replacement.closeHandoff());
        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(replacement));
    }

    @Test
    void failedRetainedHandoffCannotBeOverwrittenByACompletedPlaceholder() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        Signals failed = new Signals();
        failed.renderRelease.completeExceptionally(new IllegalStateException("render release failed"));
        gate.retainCloseHandoff("projector", failed.handoff());

        ProjectionReplacementGate.Intent<String> replacement = gate.beginIntent("projector", "session-next",
                ProjectionReplacementGate.CloseHandoff.completed());

        assertSame(failed.handoff(), replacement.closeHandoff());
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, gate.evaluate(replacement));
        assertFalse(gate.commitIfOpen(replacement, () -> {
            throw new AssertionError("failed handoff must stay closed");
        }));
    }

    @Test
    void differentOwnersHaveIndependentSlotsAndEpochs() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        Signals firstOwner = new Signals();
        ProjectionReplacementGate.Intent<String> first = gate.beginIntent("first", "session-a",
                firstOwner.handoff());
        ProjectionReplacementGate.Intent<String> second = gate.beginIntent("second", "session-b",
                ProjectionReplacementGate.CloseHandoff.completed());

        assertEquals(1L, first.epoch());
        assertEquals(1L, second.epoch());
        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(first));
        assertEquals(ProjectionReplacementGate.Decision.OPEN, gate.evaluate(second));
        assertTrue(gate.commitIfOpen(second, () -> {
        }));
        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(first));
    }

    @Test
    void sameSessionAcrossDifferentOwnersSharesOnePhysicalBarrier() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        Signals oldInstance = new Signals();
        gate.retainCloseHandoff("owner-a", "session-shared", oldInstance.handoff());

        ProjectionReplacementGate.Intent<String> moved = gate.beginIntent(
                "owner-b", "session-shared", ProjectionReplacementGate.CloseHandoff.completed());

        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(moved));
        assertFalse(gate.commitIfOpen(moved, () -> {
            throw new AssertionError("same-session texture/native owner is still closing");
        }));
        oldInstance.completeAll();
        assertEquals(ProjectionReplacementGate.Decision.OPEN, gate.evaluate(moved));
        assertTrue(gate.commitIfOpen(moved, () -> {
        }));
    }

    @Test
    void sameOwnerAcrossDifferentSessionsSharesOnePhysicalBarrier() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        Signals oldInstance = new Signals();
        gate.retainCloseHandoff("owner", "session-a", oldInstance.handoff());

        ProjectionReplacementGate.Intent<String> replacement = gate.beginIntent(
                "owner", "session-b", ProjectionReplacementGate.CloseHandoff.completed());

        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(replacement));
        oldInstance.completeAll();
        assertEquals(ProjectionReplacementGate.Decision.OPEN, gate.evaluate(replacement));
    }

    @Test
    void committedHandoffIsInstalledIntoOwnerAndSessionBeforePublication() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        Signals active = new Signals();
        ProjectionReplacementGate.Intent<String> initial = gate.beginIntent(
                "owner-a", "session-a", ProjectionReplacementGate.CloseHandoff.completed());

        assertTrue(gate.commitIfOpen(initial, () -> gate.retainCommitted(initial, active.handoff())));

        ProjectionReplacementGate.Intent<String> byOwner = gate.beginIntent(
                "owner-a", "session-b", ProjectionReplacementGate.CloseHandoff.completed());
        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(byOwner));
        gate.cancelIntent(byOwner);

        ProjectionReplacementGate.Intent<String> bySession = gate.beginIntent(
                "owner-b", "session-a", ProjectionReplacementGate.CloseHandoff.completed());
        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(bySession));

        active.completeAll();
        assertEquals(ProjectionReplacementGate.Decision.OPEN, gate.evaluate(bySession));
    }

    @Test
    void sessionSupersessionInvalidatesOldCallbackInBothDomains() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        Signals barrier = new Signals();
        ProjectionReplacementGate.Intent<String> stale = gate.beginIntent(
                "owner-a", "session", barrier.handoff());
        CompletableFuture<ProjectionReplacementGate.Decision> staleWait =
                gate.waitFor(stale, 3L, TimeUnit.SECONDS);

        ProjectionReplacementGate.Intent<String> current = gate.beginIntent(
                "owner-b", "session", ProjectionReplacementGate.CloseHandoff.completed());

        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, staleWait.join());
        assertFalse(gate.commitIfOpen(stale, () -> {
            throw new AssertionError("superseded callback must not publish");
        }));
        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(current));
        barrier.completeAll();
        assertEquals(ProjectionReplacementGate.Decision.OPEN, gate.evaluate(current));
    }

    @Test
    void timeoutSchedulerRejectionFailsClosed() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>((task, delay, unit) -> {
            throw new RejectedExecutionException("scheduler unavailable");
        });
        Signals signals = new Signals();
        ProjectionReplacementGate.Intent<String> intent = gate.beginIntent("projector", "session",
                signals.handoff());

        CompletableFuture<ProjectionReplacementGate.Decision> outcome = gate.waitFor(intent, 3L, TimeUnit.SECONDS);

        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, outcome.join());
        signals.completeAll();
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, gate.evaluate(intent));
    }

    @Test
    void latePhysicalConvergenceRequiresANewIntentAfterTimeout() {
        ManualScheduler scheduler = new ManualScheduler();
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(scheduler);
        Signals signals = new Signals();
        ProjectionReplacementGate.Intent<String> timedOut = gate.beginIntent("projector", "session-old",
                signals.handoff());
        CompletableFuture<ProjectionReplacementGate.Decision> outcome = gate.waitFor(timedOut, 3L, TimeUnit.SECONDS);
        scheduler.runNext();
        signals.completeAll();

        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, outcome.join());
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, gate.evaluate(timedOut));

        ProjectionReplacementGate.Intent<String> explicitRetry = gate.beginIntent("projector", "session-new",
                ProjectionReplacementGate.CloseHandoff.completed());
        assertNotSame(timedOut, explicitRetry);
        assertEquals(ProjectionReplacementGate.Decision.OPEN, gate.evaluate(explicitRetry));
        assertTrue(gate.commitIfOpen(explicitRetry, () -> {
        }));
    }

    @Test
    void retainedPendingEpochsAreComposedInsteadOfOverwritingEachOther() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        Signals first = new Signals();
        Signals second = new Signals();
        gate.retainCloseHandoff("projector", first.handoff());
        gate.retainCloseHandoff("projector", second.handoff());

        ProjectionReplacementGate.Intent<String> intent = gate.beginIntent(
                "projector", "replacement", ProjectionReplacementGate.CloseHandoff.completed());
        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(intent));

        first.completeAll();
        assertEquals(ProjectionReplacementGate.Decision.WAIT, gate.evaluate(intent));
        second.completeAll();
        assertEquals(ProjectionReplacementGate.Decision.OPEN, gate.evaluate(intent));
    }

    @Test
    void oneExceptionalRetainedEpochFailsTheComposedBarrierClosed() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        Signals first = new Signals();
        Signals second = new Signals();
        gate.retainCloseHandoff("projector", first.handoff());
        gate.retainCloseHandoff("projector", second.handoff());

        first.completeAll();
        second.closeReturned.completeExceptionally(new IllegalStateException("close failed"));

        ProjectionReplacementGate.Intent<String> intent = gate.beginIntent(
                "projector", "replacement", ProjectionReplacementGate.CloseHandoff.completed());
        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, gate.evaluate(intent));
    }

    @Test
    void failedCommitActionCannotBeRetriedByTheSameIntent() {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        ProjectionReplacementGate.Intent<String> intent = gate.beginIntent("projector", "session",
                ProjectionReplacementGate.CloseHandoff.completed());

        assertThrows(IllegalStateException.class, () -> gate.commitIfOpen(intent, () -> {
            throw new IllegalStateException("publication failed");
        }));

        assertEquals(ProjectionReplacementGate.Decision.FAIL_CLOSED, gate.evaluate(intent));
        assertFalse(gate.commitIfOpen(intent, () -> {
            throw new AssertionError("failed publication must not be retried");
        }));
    }

    @Test
    void concurrentCommitAttemptsPublishExactlyOnce() throws Exception {
        ProjectionReplacementGate<String> gate = new ProjectionReplacementGate<>(new ManualScheduler());
        ProjectionReplacementGate.Intent<String> intent = gate.beginIntent("projector", "session",
                ProjectionReplacementGate.CloseHandoff.completed());
        int contenders = 8;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    release.await();
                    return gate.commitIfOpen(intent, starts::incrementAndGet);
                }));
            }
            assertTrue(ready.await(5L, TimeUnit.SECONDS));
            release.countDown();

            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5L, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners);
            assertEquals(1, starts.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class Signals {
        private final CompletableFuture<Void> closeReturned = new CompletableFuture<>();
        private final CompletableFuture<Void> nativeTermination = new CompletableFuture<>();
        private final CompletableFuture<Void> decodeExit = new CompletableFuture<>();
        private final CompletableFuture<Void> renderRelease = new CompletableFuture<>();
        private final ProjectionReplacementGate.CloseHandoff handoff =
                new ProjectionReplacementGate.CloseHandoff(closeReturned, nativeTermination, decodeExit,
                        renderRelease);

        private ProjectionReplacementGate.CloseHandoff handoff() {
            return handoff;
        }

        private void completeAll() {
            closeReturned.complete(null);
            nativeTermination.complete(null);
            decodeExit.complete(null);
            renderRelease.complete(null);
        }
    }

    private static final class ManualScheduler implements ProjectionReplacementGate.TimeoutScheduler {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void schedule(Runnable task, long delay, TimeUnit unit) {
            tasks.addLast(task);
        }

        private void runNext() {
            Runnable task = tasks.removeFirst();
            task.run();
        }
    }
}
