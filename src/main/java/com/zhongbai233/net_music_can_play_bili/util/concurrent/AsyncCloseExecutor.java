package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Pure-Java bounded executor for potentially blocking resource closes. */
final class AsyncCloseExecutor {
    private static final MediaCloseProperties.ExecutorConfig PROPERTIES = MediaCloseProperties.executor();
    private static final int THREADS = PROPERTIES.threads();
    private static final int QUEUE_CAPACITY = PROPERTIES.queueCapacity();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            THREADS, THREADS, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            NetMusicThreadFactory.daemon("media-close"));

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private AsyncCloseExecutor() {
    }

    static CompletableFuture<Void> closeAsync(AutoCloseable resource, String description, Consumer<String> warning) {
        return closeAsync(resource, description, warning, false);
    }

    static CompletableFuture<Void> closeAsyncStrict(AutoCloseable resource, String description,
            Consumer<String> warning) {
        return closeAsync(resource, description, warning, true);
    }

    /**
     * Runs a close on its own daemon thread so an uncooperative provider cannot
     * permanently occupy one of the shared media-close workers.
     */
    static CompletableFuture<Void> closeAsyncIsolatedStrict(AutoCloseable resource, String description,
            Consumer<String> warning) {
        if (resource == null) {
            return CompletableFuture.completedFuture(null);
        }
        String safeDescription = description != null ? description : "media resource";
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Runnable task = closeTask(resource, safeDescription, warning, true, completion);
        try {
            NetMusicThreadFactory.daemonThread("media-close-isolated", task).start();
        } catch (RuntimeException | Error startFailure) {
            completion.completeExceptionally(startFailure);
        }
        return completion;
    }

    private static CompletableFuture<Void> closeAsync(AutoCloseable resource, String description,
            Consumer<String> warning, boolean preserveFailure) {
        if (resource == null) {
            return CompletableFuture.completedFuture(null);
        }
        String safeDescription = description != null ? description : "media resource";
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Runnable task = closeTask(resource, safeDescription, warning, preserveFailure, completion);
        try {
            EXECUTOR.execute(task);
        } catch (RejectedExecutionException error) {
            warning.accept("媒体关闭队列已满，改用独立后台线程关闭 " + safeDescription);
            NetMusicThreadFactory.daemonThread("media-close-emergency", task).start();
        }
        return completion;
    }

    private static Runnable closeTask(AutoCloseable resource, String safeDescription, Consumer<String> warning,
            boolean preserveFailure, CompletableFuture<Void> completion) {
        return () -> {
            try {
                resource.close();
            } catch (Throwable error) {
                if (preserveFailure) {
                    completion.completeExceptionally(error);
                }
                try {
                    warning.accept("关闭 " + safeDescription + " 失败: " + error);
                } catch (Throwable warningFailure) {
                    error.addSuppressed(warningFailure);
                }
                if (preserveFailure) {
                    return;
                }
                if (error instanceof Error fatal) {
                    completion.completeExceptionally(fatal);
                    throw fatal;
                }
            } finally {
                if (!completion.isDone()) {
                    completion.complete(null);
                }
            }
        };
    }
}
