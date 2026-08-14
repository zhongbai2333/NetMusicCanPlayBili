package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseExecutor;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks every decoder-owned stream and exposes one physical-close barrier. */
class NativeVideoTrackedInputs {
    private final IdentityHashMap<InputStream, TrackedInput> aliases = new IdentityHashMap<>();
    private final List<TrackedInput> inputs = new ArrayList<>();
    private final InputCloseScheduler closeScheduler;
    private CompletableFuture<Void> completion = CompletableFuture.completedFuture(null);
    private boolean closing;

    NativeVideoTrackedInputs() {
        this(MediaCloseExecutor::closeAsyncStrict);
    }

    NativeVideoTrackedInputs(InputCloseScheduler closeScheduler) {
        this.closeScheduler = closeScheduler;
    }

    InputStream track(InputStream stream) {
        if (stream == null) {
            throw new IllegalArgumentException("stream must not be null");
        }
        TrackedInput tracked;
        boolean closeNow;
        synchronized (this) {
            tracked = aliases.get(stream);
            if (tracked == null) {
                tracked = addTracked(stream);
            }
            closeNow = closing;
        }
        if (closeNow) {
            tracked.closeAsync();
        }
        return tracked.exposedStream();
    }

    CompletableFuture<Void> closeAsync(InputStream stream) {
        if (stream == null) {
            return CompletableFuture.completedFuture(null);
        }
        TrackedInput tracked;
        synchronized (this) {
            tracked = aliases.get(stream);
            if (tracked == null) {
                tracked = addTracked(stream);
            }
        }
        tracked.closeAsync();
        return tracked.outcome();
    }

    void beginClose() {
        List<TrackedInput> snapshot;
        synchronized (this) {
            closing = true;
            snapshot = new ArrayList<>(inputs);
        }
        for (TrackedInput tracked : snapshot) {
            tracked.closeAsync();
        }
    }

    synchronized CompletableFuture<Void> completionSnapshot() {
        return completion;
    }

    synchronized int trackedCount() {
        return inputs.size();
    }

    private TrackedInput addTracked(InputStream stream) {
        TrackedInput tracked = new TrackedInput(stream, closeScheduler);
        inputs.add(tracked);
        aliases.put(stream, tracked);
        aliases.put(tracked.exposedStream(), tracked);
        completion = CompletableFuture.allOf(completion, tracked.outcome());
        return tracked;
    }

    @FunctionalInterface
    interface InputCloseScheduler {
        CompletableFuture<Void> closeAsync(AutoCloseable resource, String description);
    }

    private static final class TrackedInput {
        private final InputStream stream;
        private final InputStream exposedStream;
        private final InputCloseScheduler closeScheduler;
        private final AtomicBoolean closeStarted = new AtomicBoolean(false);
        private final CompletableFuture<Void> outcome = new CompletableFuture<>();

        private TrackedInput(InputStream stream, InputCloseScheduler closeScheduler) {
            this.stream = stream;
            this.closeScheduler = closeScheduler;
            this.exposedStream = new FilterInputStream(stream) {
                @Override
                public void close() {
                    TrackedInput.this.closeAsync();
                }
            };
        }

        private InputStream exposedStream() {
            return exposedStream;
        }

        private CompletableFuture<Void> outcome() {
            return outcome;
        }

        private void closeAsync() {
            if (!closeStarted.compareAndSet(false, true)) {
                return;
            }
            try {
                closeScheduler.closeAsync(stream, "native video tracked input")
                        .whenComplete((ignored, error) -> {
                            if (error == null) {
                                outcome.complete(null);
                            } else {
                                outcome.completeExceptionally(error);
                            }
                        });
            } catch (Throwable schedulingFailure) {
                outcome.completeExceptionally(schedulingFailure);
            }
        }
    }
}
