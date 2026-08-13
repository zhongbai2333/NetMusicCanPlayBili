package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellableTaskFutureTest {
    @Test
    void cancellationInterruptsTheActualWorker() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            CancellableTaskFuture<String> task = CancellableTaskFuture.submit(executor, () -> {
                started.countDown();
                try {
                    Thread.sleep(60_000L);
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return "late";
            });

            assertTrue(started.await(2L, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));
            assertTrue(interrupted.await(2L, TimeUnit.SECONDS));
            assertTrue(task.isCancelled());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelWorkerStillInterruptsAfterTimeoutCompletesTheFuture() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            CancellableTaskFuture<String> task = CancellableTaskFuture.submit(executor, () -> {
                started.countDown();
                try {
                    Thread.sleep(60_000L);
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return "late";
            });

            assertTrue(started.await(2L, TimeUnit.SECONDS));
            assertTrue(task.complete(null));
            task.cancelWorker();
            assertTrue(interrupted.await(2L, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void normalCompletionIsPreserved() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            AtomicBoolean ran = new AtomicBoolean();
            CancellableTaskFuture<String> task = CancellableTaskFuture.submit(executor, () -> {
                ran.set(true);
                return "ready";
            });

            assertEquals("ready", task.get(2L, TimeUnit.SECONDS));
            assertTrue(ran.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationActionRunsExactlyOnceBeforeRepeatedWorkerCancellation() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            AtomicInteger cancellationActions = new AtomicInteger();
            CancellableTaskFuture<Void> task = CancellableTaskFuture.submit(executor, () -> {
                started.countDown();
                try {
                    Thread.sleep(60_000L);
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return null;
            }, cancellationActions::incrementAndGet);

            assertTrue(started.await(2L, TimeUnit.SECONDS));
            assertTrue(task.cancel(true));
            task.cancel(true);
            task.cancelWorker();

            assertTrue(interrupted.await(2L, TimeUnit.SECONDS));
            assertEquals(1, cancellationActions.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
