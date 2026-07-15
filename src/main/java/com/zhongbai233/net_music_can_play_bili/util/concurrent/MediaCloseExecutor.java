package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Shared bounded executor for potentially blocking media resource closes. */
public final class MediaCloseExecutor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int THREADS = Math.max(1, Integer.getInteger("ncpb.media.close.threads", 2));
    private static final int QUEUE_CAPACITY = Math.max(1, Integer.getInteger("ncpb.media.close.queue", 32));
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            THREADS, THREADS, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            NetMusicThreadFactory.daemon("media-close"));

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private MediaCloseExecutor() {
    }

    public static void closeAsync(AutoCloseable resource, String description) {
        if (resource == null) {
            return;
        }
        CloseTask task = new CloseTask(resource, description != null ? description : "media resource");
        try {
            EXECUTOR.execute(task);
        } catch (RejectedExecutionException e) {
            LOGGER.warn("媒体关闭队列已满，当前线程同步关闭 {}", task.description());
            task.run();
        }
    }

    private record CloseTask(AutoCloseable resource, String description) implements Runnable {
        @Override
        public void run() {
            try {
                resource.close();
            } catch (Exception e) {
                LOGGER.warn("关闭 {} 失败: {}", description, e.toString());
            }
        }
    }
}