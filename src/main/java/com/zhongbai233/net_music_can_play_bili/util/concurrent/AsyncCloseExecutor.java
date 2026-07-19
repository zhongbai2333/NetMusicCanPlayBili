package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Pure-Java bounded executor for potentially blocking resource closes. */
final class AsyncCloseExecutor {
    private static final int THREADS = Math.max(1, Integer.getInteger("ncpb.media.close.threads", 2));
    private static final int QUEUE_CAPACITY = Math.max(1, Integer.getInteger("ncpb.media.close.queue", 32));
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
        if (resource == null) {
            return CompletableFuture.completedFuture(null);
        }
        String safeDescription = description != null ? description : "media resource";
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                resource.close();
            } catch (Exception error) {
                warning.accept("关闭 " + safeDescription + " 失败: " + error);
            } finally {
                completion.complete(null);
            }
        };
        try {
            EXECUTOR.execute(task);
        } catch (RejectedExecutionException error) {
            warning.accept("媒体关闭队列已满，改用独立后台线程关闭 " + safeDescription);
            NetMusicThreadFactory.daemonThread("media-close-emergency", task).start();
        }
        return completion;
    }
}