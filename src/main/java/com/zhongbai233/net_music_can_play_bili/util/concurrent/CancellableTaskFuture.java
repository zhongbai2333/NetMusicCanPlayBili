package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 将 CompletableFuture 的取消传播到实际 executor worker。 */
public final class CancellableTaskFuture<T> extends CompletableFuture<T> {
    private final AtomicReference<Future<?>> worker = new AtomicReference<>();
    private final AtomicBoolean cancellationSignalled = new AtomicBoolean();
    private final Runnable cancellationAction;

    private CancellableTaskFuture(Runnable cancellationAction) {
        this.cancellationAction = Objects.requireNonNull(cancellationAction, "cancellationAction");
    }

    public static <T> CancellableTaskFuture<T> submit(ExecutorService executor, Supplier<T> supplier) {
        return submit(executor, supplier, () -> {
        });
    }

    /**
     * Submits a worker and runs {@code cancellationAction} exactly once before interrupting it.
     * This lets owners close a blocking response body in addition to cancelling the worker.
     */
    public static <T> CancellableTaskFuture<T> submit(ExecutorService executor, Supplier<T> supplier,
            Runnable cancellationAction) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(supplier, "supplier");
        CancellableTaskFuture<T> completion = new CancellableTaskFuture<>(cancellationAction);
        try {
            Future<?> submitted = executor.submit(() -> {
                if (completion.isCancelled()) {
                    return;
                }
                try {
                    completion.complete(supplier.get());
                } catch (Throwable error) {
                    completion.completeExceptionally(error);
                }
            });
            completion.bindWorker(submitted);
        } catch (Throwable error) {
            completion.completeExceptionally(error);
        }
        return completion;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean cancelled = super.cancel(mayInterruptIfRunning);
        if (cancelled || isCancelled()) {
            signalCancellation();
        }
        interruptWorker();
        return cancelled;
    }

    /** 即使 timeout 已先完成 future，也继续中断底层 worker。 */
    public void cancelWorker() {
        signalCancellation();
        interruptWorker();
    }

    private void signalCancellation() {
        if (!cancellationSignalled.compareAndSet(false, true)) {
            return;
        }
        try {
            cancellationAction.run();
        } catch (Throwable ignored) {
            // Cancellation must still reach the worker even if best-effort resource close fails.
        }
    }

    private void interruptWorker() {
        Future<?> submitted = worker.get();
        if (submitted != null) {
            submitted.cancel(true);
        }
    }

    private void bindWorker(Future<?> submitted) {
        if (!worker.compareAndSet(null, submitted)) {
            submitted.cancel(true);
            return;
        }
        if (isCancelled()) {
            submitted.cancel(true);
        }
    }
}
