package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-Java admission gate for projection decoder replacement.
 *
 * <p>A decoder conflicts with both its physical owner and its playback session. The session domain matters even
 * when an instance moves to another owner because session texture identifiers are deterministic. Both domains are
 * reserved, superseded and committed under this object's single monitor, so an asynchronous callback cannot pass
 * an owner check and then race a session detach (or the reverse).</p>
 */
final class ProjectionReplacementGate<K> {
    enum Decision {
        OPEN,
        WAIT,
        FAIL_CLOSED
    }

    /** Stable physical-close signals captured before an instance is detached from business state. */
    record CloseHandoff(CompletableFuture<Void> closeReturned,
            CompletableFuture<Void> nativeTermination,
            CompletableFuture<Void> decodeExit,
            CompletableFuture<Void> renderRelease) {
        CloseHandoff {
            Objects.requireNonNull(closeReturned, "closeReturned");
            Objects.requireNonNull(nativeTermination, "nativeTermination");
            Objects.requireNonNull(decodeExit, "decodeExit");
            Objects.requireNonNull(renderRelease, "renderRelease");
        }

        static CloseHandoff completed() {
            CompletableFuture<Void> completed = CompletableFuture.completedFuture(null);
            return new CloseHandoff(completed, completed, completed, completed);
        }

        private List<CompletableFuture<Void>> signals() {
            return List.of(closeReturned, nativeTermination, decodeExit, renderRelease);
        }
    }

    /** Immutable identity for one desired replacement across its owner and session domains. */
    record Intent<K>(K ownerKey, String desiredSessionId, long epoch, long sessionEpoch,
            CloseHandoff closeHandoff, Object stateIdentity, Object gateIdentity) {
        Intent {
            Objects.requireNonNull(ownerKey, "ownerKey");
            desiredSessionId = normalizeSession(desiredSessionId);
            Objects.requireNonNull(closeHandoff, "closeHandoff");
            Objects.requireNonNull(stateIdentity, "stateIdentity");
            Objects.requireNonNull(gateIdentity, "gateIdentity");
        }
    }

    @FunctionalInterface
    interface TimeoutScheduler {
        void schedule(Runnable task, long delay, TimeUnit unit);
    }

    private final Map<K, DomainSlot> ownerSlots = new HashMap<>();
    private final Map<String, DomainSlot> sessionSlots = new HashMap<>();
    private final TimeoutScheduler timeoutScheduler;
    private final Object gateIdentity = new Object();

    ProjectionReplacementGate() {
        this((task, delay, unit) -> CompletableFuture.delayedExecutor(delay, unit).execute(task));
    }

    ProjectionReplacementGate(TimeoutScheduler timeoutScheduler) {
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
    }

    /**
     * Starts a desired replacement, atomically superseding older callbacks that share either domain.
     */
    Intent<K> beginIntent(K ownerKey, String desiredSessionId, CloseHandoff proposedHandoff) {
        Objects.requireNonNull(ownerKey, "ownerKey");
        Objects.requireNonNull(proposedHandoff, "proposedHandoff");
        String sessionId = normalizeSession(desiredSessionId);
        List<Completion> superseded = new ArrayList<>(2);
        Intent<K> intent;
        synchronized (this) {
            DomainSlot ownerSlot = ownerSlots.computeIfAbsent(ownerKey, ignored -> new DomainSlot());
            DomainSlot sessionSlot = sessionSlot(sessionId, true);
            // Preflight every epoch before invalidating waiters or changing retained barriers. If an epoch is
            // exhausted the operation must be observationally atomic: the currently active intent remains valid.
            long nextOwnerEpoch = nextEpoch(ownerSlot.epoch);
            long nextSessionEpoch = sessionSlot != null ? nextEpoch(sessionSlot.epoch) : 0L;
            invalidateDistinct(superseded, ownerSlot.current, sessionSlot != null ? sessionSlot.current : null);

            ownerSlot.retainedHandoff = stableHandoff(ownerSlot.retainedHandoff, proposedHandoff);
            if (sessionSlot != null) {
                sessionSlot.retainedHandoff = stableHandoff(sessionSlot.retainedHandoff, proposedHandoff);
            }
            CloseHandoff barrier = ownerSlot.retainedHandoff;
            if (sessionSlot != null) {
                barrier = stableHandoff(barrier, sessionSlot.retainedHandoff);
            }
            ownerSlot.epoch = nextOwnerEpoch;
            if (sessionSlot != null) {
                sessionSlot.epoch = nextSessionEpoch;
            }
            IntentState state = new IntentState(ownerKey, sessionId, ownerSlot.epoch, nextSessionEpoch, barrier);
            ownerSlot.current = state;
            if (sessionSlot != null) {
                sessionSlot.current = state;
            }
            intent = new Intent<>(ownerKey, sessionId, state.ownerEpoch, state.sessionEpoch,
                    barrier, state, gateIdentity);
        }
        completeAll(superseded);
        return intent;
    }

    /** Retains a close handoff in the owner domain only. Kept for pure owner-domain callers and tests. */
    void retainCloseHandoff(K ownerKey, CloseHandoff closeHandoff) {
        retainCloseHandoff(ownerKey, "", closeHandoff);
    }

    /**
     * Records a close handoff in both conflict domains. Pending intents sharing either domain are invalidated.
     */
    void retainCloseHandoff(K ownerKey, String sessionId, CloseHandoff closeHandoff) {
        Objects.requireNonNull(ownerKey, "ownerKey");
        Objects.requireNonNull(closeHandoff, "closeHandoff");
        String normalizedSession = normalizeSession(sessionId);
        List<Completion> superseded = new ArrayList<>(2);
        synchronized (this) {
            DomainSlot ownerSlot = ownerSlots.computeIfAbsent(ownerKey, ignored -> new DomainSlot());
            DomainSlot sessionSlot = sessionSlot(normalizedSession, true);
            long nextOwnerEpoch = nextEpoch(ownerSlot.epoch);
            long nextSessionEpoch = sessionSlot != null ? nextEpoch(sessionSlot.epoch) : 0L;
            invalidateDistinct(superseded, ownerSlot.current, sessionSlot != null ? sessionSlot.current : null);

            ownerSlot.epoch = nextOwnerEpoch;
            ownerSlot.retainedHandoff = stableHandoff(ownerSlot.retainedHandoff, closeHandoff);
            ownerSlot.current = null;
            if (sessionSlot != null) {
                sessionSlot.epoch = nextSessionEpoch;
                sessionSlot.retainedHandoff = stableHandoff(sessionSlot.retainedHandoff, closeHandoff);
                sessionSlot.current = null;
            }
        }
        completeAll(superseded);
    }

    /**
     * Installs the newly published instance's born-pending handoff into both domains while commit still owns the
     * gate monitor. This closes the interval between registry publication and a later logical removal.
     */
    synchronized void retainCommitted(Intent<K> intent, CloseHandoff closeHandoff) {
        Objects.requireNonNull(closeHandoff, "closeHandoff");
        IntentState state = matchingState(intent);
        if (state == null || !state.committed) {
            throw new IllegalStateException("projection replacement intent is not the active commit");
        }
        DomainSlot ownerSlot = ownerSlots.get(intent.ownerKey());
        ownerSlot.retainedHandoff = stableHandoff(ownerSlot.retainedHandoff, closeHandoff);
        ownerSlot.current = null;
        DomainSlot sessionSlot = sessionSlot(intent.desiredSessionId(), false);
        if (sessionSlot != null) {
            sessionSlot.retainedHandoff = stableHandoff(sessionSlot.retainedHandoff, closeHandoff);
            sessionSlot.current = null;
        }
    }

    /** Invalidates a never-published intent while preserving all retained physical barriers. */
    void cancelIntent(Intent<K> intent) {
        Completion completion;
        synchronized (this) {
            IntentState state = matchingState(intent);
            if (state == null) {
                return;
            }
            state.terminalDecision = Decision.FAIL_CLOSED;
            detachState(state);
            completion = claimWaiter(state, Decision.FAIL_CLOSED);
        }
        complete(completion);
    }

    /** Returns the current synchronous admission decision for an intent. */
    synchronized Decision evaluate(Intent<K> intent) {
        IntentState state = matchingState(intent);
        return state != null ? evaluate(state) : Decision.FAIL_CLOSED;
    }

    /** Returns whether an intent is still the active, uncommitted intent in both domains. */
    synchronized boolean isCurrent(Intent<K> intent) {
        IntentState state = matchingState(intent);
        return state != null && !state.committed;
    }

    /**
     * Waits without blocking a thread. The first physical decision or timeout wins permanently for this intent.
     */
    CompletableFuture<Decision> waitFor(Intent<K> intent, long timeout, TimeUnit unit) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0L) {
            throw new IllegalArgumentException("timeout must be >= 0");
        }

        IntentState state;
        Waiter waiter;
        synchronized (this) {
            state = matchingState(intent);
            if (state == null) {
                return CompletableFuture.completedFuture(Decision.FAIL_CLOSED);
            }
            Decision decision = evaluate(state);
            if (decision != Decision.WAIT) {
                return CompletableFuture.completedFuture(decision);
            }
            if (state.waiter != null) {
                return state.waiter.outcome;
            }
            waiter = new Waiter();
            state.waiter = waiter;
        }

        try {
            timeoutScheduler.schedule(() -> resolveTimeout(intent, state, waiter), timeout, unit);
        } catch (RuntimeException schedulingFailure) {
            resolveTimeout(intent, state, waiter);
        }
        for (CompletableFuture<Void> signal : state.closeHandoff.signals()) {
            signal.whenComplete((ignored, error) -> resolveSignals(intent, state, waiter));
        }
        resolveSignals(intent, state, waiter);
        return waiter.outcome;
    }

    /**
     * Atomically verifies both domains before publishing/starting. The action executes under the short gate
     * critical section; callers should install the new handoff with {@link #retainCommitted(Intent, CloseHandoff)}
     * before starting work that may allocate native or render resources.
     */
    synchronized boolean commitIfOpen(Intent<K> intent, Runnable action) {
        Objects.requireNonNull(action, "action");
        IntentState state = matchingState(intent);
        if (state == null || state.committed || evaluate(state) != Decision.OPEN) {
            return false;
        }
        state.committed = true;
        try {
            action.run();
            return true;
        } catch (RuntimeException | Error failure) {
            state.terminalDecision = Decision.FAIL_CLOSED;
            detachState(state);
            throw failure;
        }
    }

    private void resolveSignals(Intent<K> intent, IntentState expectedState, Waiter expectedWaiter) {
        Completion completion = null;
        synchronized (this) {
            IntentState state = matchingState(intent);
            if (state != expectedState || state.waiter != expectedWaiter || expectedWaiter.claimed) {
                return;
            }
            Decision decision = physicalDecision(state.closeHandoff);
            if (decision != Decision.WAIT) {
                state.terminalDecision = decision;
                completion = claimWaiter(state, decision);
            }
        }
        complete(completion);
    }

    private void resolveTimeout(Intent<K> intent, IntentState expectedState, Waiter expectedWaiter) {
        Completion completion = null;
        synchronized (this) {
            IntentState state = matchingState(intent);
            if (state != expectedState || state.waiter != expectedWaiter || expectedWaiter.claimed) {
                return;
            }
            state.terminalDecision = Decision.FAIL_CLOSED;
            completion = claimWaiter(state, Decision.FAIL_CLOSED);
        }
        complete(completion);
    }

    private IntentState matchingState(Intent<K> intent) {
        if (intent == null || intent.gateIdentity() != gateIdentity) {
            return null;
        }
        DomainSlot ownerSlot = ownerSlots.get(intent.ownerKey());
        if (ownerSlot == null || ownerSlot.current == null || ownerSlot.current != intent.stateIdentity()) {
            return null;
        }
        IntentState state = ownerSlot.current;
        if (ownerSlot.epoch != intent.epoch()
                || state.ownerEpoch != intent.epoch() || state.closeHandoff != intent.closeHandoff()
                || !state.sessionId.equals(intent.desiredSessionId())) {
            return null;
        }
        DomainSlot sessionSlot = sessionSlot(intent.desiredSessionId(), false);
        if (sessionSlot != null && (sessionSlot.current != state || sessionSlot.epoch != intent.sessionEpoch()
                || state.sessionEpoch != intent.sessionEpoch())) {
            return null;
        }
        return state;
    }

    private void invalidateDistinct(List<Completion> completions, IntentState first, IntentState second) {
        IdentityHashMap<IntentState, Boolean> seen = new IdentityHashMap<>();
        if (first != null) {
            seen.put(first, Boolean.TRUE);
            invalidate(first, completions);
        }
        if (second != null && !seen.containsKey(second)) {
            invalidate(second, completions);
        }
    }

    private void invalidate(IntentState state, List<Completion> completions) {
        state.terminalDecision = Decision.FAIL_CLOSED;
        detachState(state);
        Completion completion = claimWaiter(state, Decision.FAIL_CLOSED);
        if (completion != null) {
            completions.add(completion);
        }
    }

    private void detachState(IntentState state) {
        @SuppressWarnings("unchecked")
        K ownerKey = (K) state.ownerKey;
        DomainSlot ownerSlot = ownerSlots.get(ownerKey);
        if (ownerSlot != null && ownerSlot.current == state) {
            ownerSlot.current = null;
        }
        DomainSlot sessionSlot = sessionSlot(state.sessionId, false);
        if (sessionSlot != null && sessionSlot.current == state) {
            sessionSlot.current = null;
        }
    }

    private DomainSlot sessionSlot(String sessionId, boolean create) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return create ? sessionSlots.computeIfAbsent(sessionId, ignored -> new DomainSlot())
                : sessionSlots.get(sessionId);
    }

    private Decision evaluate(IntentState state) {
        if (state.terminalDecision != null) {
            return state.terminalDecision;
        }
        Decision decision = physicalDecision(state.closeHandoff);
        if (decision != Decision.WAIT) {
            state.terminalDecision = decision;
        }
        return decision;
    }

    private static Decision physicalDecision(CloseHandoff handoff) {
        boolean pending = false;
        for (CompletableFuture<Void> signal : handoff.signals()) {
            if (!signal.isDone()) {
                pending = true;
                continue;
            }
            if (signal.isCancelled() || signal.isCompletedExceptionally()) {
                return Decision.FAIL_CLOSED;
            }
            try {
                signal.join();
            } catch (RuntimeException failure) {
                return Decision.FAIL_CLOSED;
            }
        }
        return pending ? Decision.WAIT : Decision.OPEN;
    }

    private static CloseHandoff stableHandoff(CloseHandoff retained, CloseHandoff proposed) {
        if (retained == null || physicalDecision(retained) == Decision.OPEN) {
            return proposed;
        }
        if (retained == proposed || physicalDecision(proposed) == Decision.OPEN) {
            return retained;
        }
        return new CloseHandoff(
                requireBoth(retained.closeReturned(), proposed.closeReturned()),
                requireBoth(retained.nativeTermination(), proposed.nativeTermination()),
                requireBoth(retained.decodeExit(), proposed.decodeExit()),
                requireBoth(retained.renderRelease(), proposed.renderRelease()));
    }

    private static CompletableFuture<Void> requireBoth(CompletableFuture<Void> first,
            CompletableFuture<Void> second) {
        if (first == second) {
            return first;
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(2);
        java.util.function.BiConsumer<Void, Throwable> completion = (ignored, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else if (remaining.decrementAndGet() == 0) {
                result.complete(null);
            }
        };
        first.whenComplete(completion);
        second.whenComplete(completion);
        return result;
    }

    private static Completion claimWaiter(IntentState state, Decision decision) {
        Waiter waiter = state.waiter;
        if (waiter == null || waiter.claimed) {
            return null;
        }
        waiter.claimed = true;
        state.waiter = null;
        return new Completion(waiter.outcome, decision);
    }

    private static void completeAll(List<Completion> completions) {
        for (Completion completion : completions) {
            complete(completion);
        }
    }

    private static void complete(Completion completion) {
        if (completion != null) {
            completion.outcome.complete(completion.decision);
        }
    }

    private static long nextEpoch(long current) {
        if (current == Long.MAX_VALUE) {
            throw new IllegalStateException("projection replacement intent epoch exhausted");
        }
        return current + 1L;
    }

    private static String normalizeSession(String desiredSessionId) {
        return desiredSessionId != null ? desiredSessionId : "";
    }

    private static final class DomainSlot {
        private long epoch;
        private CloseHandoff retainedHandoff;
        private IntentState current;
    }

    private static final class IntentState {
        private final Object ownerKey;
        private final String sessionId;
        private final long ownerEpoch;
        private final long sessionEpoch;
        private final CloseHandoff closeHandoff;
        private Decision terminalDecision;
        private Waiter waiter;
        private boolean committed;

        private IntentState(Object ownerKey, String sessionId, long ownerEpoch, long sessionEpoch,
                CloseHandoff closeHandoff) {
            this.ownerKey = ownerKey;
            this.sessionId = sessionId;
            this.ownerEpoch = ownerEpoch;
            this.sessionEpoch = sessionEpoch;
            this.closeHandoff = closeHandoff;
        }

    }

    private static final class Waiter {
        private final CompletableFuture<Decision> outcome = new CompletableFuture<>();
        private boolean claimed;
    }

    private record Completion(CompletableFuture<Decision> outcome, Decision decision) {
    }
}
