package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Shared daemon executor for user-triggered blocking media metadata/preview I/O.
 *
 * <p>Keeping these requests away from {@link java.util.concurrent.ForkJoinPool#commonPool()} prevents a slow CDN
 * or Bilibili API request from starving unrelated JVM background work.</p>
 */
public final class MediaIoExecutor {
    private static final int CORE_THREADS = 2;
    private static final int MAX_THREADS = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 4);
    private static final int QUEUE_CAPACITY = 64;
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            CORE_THREADS, MAX_THREADS, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY), NetMusicThreadFactory.daemon("media-io"));

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private MediaIoExecutor() {
    }

    public static <T> CompletableFuture<T> supply(Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        try {
            return CompletableFuture.supplyAsync(task, EXECUTOR);
        } catch (RejectedExecutionException saturated) {
            return CompletableFuture.failedFuture(saturated);
        }
    }
}
