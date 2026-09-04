package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/** Shared bounded executor for potentially blocking media resource closes. */
public final class MediaCloseExecutor {
    private MediaCloseExecutor() {
    }

    public static CompletableFuture<Void> closeAsync(AutoCloseable resource, String description) {
        return AsyncCloseExecutor.closeAsync(resource, description, message -> logger().warn(message));
    }

    /** Preserves a close exception for physical-resource admission barriers. */
    public static CompletableFuture<Void> closeAsyncStrict(AutoCloseable resource, String description) {
        return AsyncCloseExecutor.closeAsyncStrict(resource, description, message -> logger().warn(message));
    }

    /** Isolates an unbounded close from the shared media-close pool. */
    public static CompletableFuture<Void> closeAsyncIsolatedStrict(AutoCloseable resource, String description) {
        return AsyncCloseExecutor.closeAsyncIsolatedStrict(resource, description, message -> logger().warn(message));
    }

    private static Logger logger() {
        return LoggerHolder.INSTANCE;
    }

    private static final class LoggerHolder {
        private static final Logger INSTANCE = com.mojang.logging.LogUtils.getLogger();

        private LoggerHolder() {
        }
    }
}
