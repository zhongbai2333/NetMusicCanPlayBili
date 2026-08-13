package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects the physical-close signals produced over one playback instance's lifetime.
 *
 * <p>The four futures exposed by {@link #snapshot()} are stable from construction. Calling
 * {@link #seal(CompletableFuture)} records the stop boundary, but decoder and close signals may still be
 * attached while a decode operation registered before that boundary is running. Registration closes atomically
 * only after every registered decode exit has completed.</p>
 *
 * <p>A decode worker must attach its decoder/close signals before completing its registered exit future. A
 * registration attempted after that exit closes the registration window is rejected instead of being silently
 * omitted from the replacement barrier.</p>
 */
final class VideoPhysicalCloseHandoff {
    private final Object lock = new Object();
    private final Set<CompletableFuture<Void>> closeSignals =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<CompletableFuture<Void>> nativeSignals =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<CompletableFuture<Void>> decodeSignals =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private final CompletableFuture<Void> closeReturned = new CompletableFuture<>();
    private final CompletableFuture<Void> nativeTermination = new CompletableFuture<>();
    private final CompletableFuture<Void> decodeExit = new CompletableFuture<>();
    private final CompletableFuture<Void> renderRelease = new CompletableFuture<>();
    private final ProjectionReplacementGate.CloseHandoff stableSnapshot =
            new ProjectionReplacementGate.CloseHandoff(
                    closeReturned, nativeTermination, decodeExit, renderRelease);

    private boolean sealRequested;
    private boolean registrationsClosed;
    private CompletableFuture<Void> sealedRenderSignal;

    /** Registers a decode generation before its worker is started. */
    void beginDecode(CompletableFuture<Void> exit) {
        CompletableFuture<Void> signal = Objects.requireNonNull(exit, "exit");
        boolean added;
        synchronized (lock) {
            if (sealRequested) {
                throw new IllegalStateException("cannot begin a decode generation after seal");
            }
            ensureRegistrationOpen();
            added = decodeSignals.add(signal);
        }
        if (added) {
            watchDecode(signal);
        }
    }

    /**
     * Attaches the stable native-termination signal of a decoder created by a registered decode generation.
     * This generic future API keeps the collector independent of the native decoder implementation.
     */
    void attachDecoder(CompletableFuture<Void> termination) {
        CompletableFuture<Void> signal = Objects.requireNonNull(termination, "termination");
        boolean added;
        synchronized (lock) {
            ensureRegistrationOpen();
            added = nativeSignals.add(signal);
        }
        if (added) {
            watchFailure(signal, nativeTermination);
        }
    }

    /**
     * Atomically attaches the close-returned, native-termination, and decode-exit signals for one generation.
     */
    void attachClose(CompletableFuture<Void> close, CompletableFuture<Void> termination,
            CompletableFuture<Void> exit) {
        CompletableFuture<Void> closeSignal = Objects.requireNonNull(close, "close");
        CompletableFuture<Void> nativeSignal = Objects.requireNonNull(termination, "termination");
        CompletableFuture<Void> exitSignal = Objects.requireNonNull(exit, "exit");
        boolean closeAdded;
        boolean nativeAdded;
        boolean decodeAdded;
        synchronized (lock) {
            ensureRegistrationOpen();
            if (sealRequested && !decodeSignals.contains(exitSignal)) {
                throw new IllegalStateException("decode exit was not registered before seal");
            }
            closeAdded = closeSignals.add(closeSignal);
            nativeAdded = nativeSignals.add(nativeSignal);
            decodeAdded = decodeSignals.add(exitSignal);
        }
        if (closeAdded) {
            watchFailure(closeSignal, closeReturned);
        }
        if (nativeAdded) {
            watchFailure(nativeSignal, nativeTermination);
        }
        if (decodeAdded) {
            watchDecode(exitSignal);
        }
    }

    /**
     * Records the stop boundary and the render-thread release signal. The same signal may be sealed repeatedly.
     */
    void seal(CompletableFuture<Void> release) {
        CompletableFuture<Void> signal = Objects.requireNonNull(release, "release");
        synchronized (lock) {
            if (sealRequested) {
                if (sealedRenderSignal != signal) {
                    throw new IllegalStateException("handoff was already sealed with another render signal");
                }
                return;
            }
            sealRequested = true;
            sealedRenderSignal = signal;
        }
        bridge(signal, renderRelease);
        tryCloseRegistration();
    }

    /** Returns the stable physical-close handoff. */
    ProjectionReplacementGate.CloseHandoff snapshot() {
        return stableSnapshot;
    }

    private void watchDecode(CompletableFuture<Void> signal) {
        signal.whenComplete((ignored, error) -> {
            if (error != null) {
                decodeExit.completeExceptionally(error);
            }
            tryCloseRegistration();
        });
    }

    private void tryCloseRegistration() {
        List<CompletableFuture<Void>> closes;
        List<CompletableFuture<Void>> natives;
        List<CompletableFuture<Void>> exits;
        synchronized (lock) {
            if (!sealRequested || registrationsClosed || !allDone(decodeSignals)) {
                return;
            }
            registrationsClosed = true;
            closes = new ArrayList<>(closeSignals);
            natives = new ArrayList<>(nativeSignals);
            exits = new ArrayList<>(decodeSignals);
        }
        bridge(allNormally(closes), closeReturned);
        bridge(allNormally(natives), nativeTermination);
        bridge(allNormally(exits), decodeExit);
    }

    private void ensureRegistrationOpen() {
        if (registrationsClosed) {
            throw new IllegalStateException("physical-close registration is closed");
        }
    }

    private static boolean allDone(Set<CompletableFuture<Void>> signals) {
        for (CompletableFuture<Void> signal : signals) {
            if (!signal.isDone()) {
                return false;
            }
        }
        return true;
    }

    private static CompletableFuture<Void> allNormally(List<CompletableFuture<Void>> signals) {
        if (signals.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(signals.size());
        for (CompletableFuture<Void> signal : signals) {
            signal.whenComplete((ignored, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                } else if (remaining.decrementAndGet() == 0) {
                    result.complete(null);
                }
            });
        }
        return result;
    }

    private static void watchFailure(CompletableFuture<Void> source, CompletableFuture<Void> target) {
        source.whenComplete((ignored, error) -> {
            if (error != null) {
                target.completeExceptionally(error);
            }
        });
    }

    private static void bridge(CompletableFuture<Void> source, CompletableFuture<Void> target) {
        source.whenComplete((ignored, error) -> {
            if (error == null) {
                target.complete(null);
            } else {
                target.completeExceptionally(error);
            }
        });
    }
}
