package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncCloseExecutorTest {
    @Test
    void closesMediaResourcesOffCallerThread() {
        String callerThread = Thread.currentThread().getName();
        AtomicReference<String> closeThread = new AtomicReference<>();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> AsyncCloseExecutor.closeAsync(
                () -> closeThread.set(Thread.currentThread().getName()), "test resource", ignored -> {
                }).join());

        assertNotNull(closeThread.get());
        assertNotEquals(callerThread, closeThread.get());
    }

    @Test
    void strictClosePreservesResourceFailureForPhysicalBarriers() {
        CompletableFuture<Void> result = AsyncCloseExecutor.closeAsyncStrict(
                () -> {
                    throw new IOException("close failed");
                }, "strict test resource", ignored -> {
                });

        assertThrows(CompletionException.class, result::join);
    }

    @Test
    void strictCloseAlsoPreservesFatalResourceFailure() {
        CompletableFuture<Void> result = AsyncCloseExecutor.closeAsyncStrict(
                () -> {
                    throw new AssertionError("native close failed");
                }, "test native handle", ignored -> {
                });

        assertThrows(CompletionException.class, result::join);
        assertTrue(result.isCompletedExceptionally());
    }
}
