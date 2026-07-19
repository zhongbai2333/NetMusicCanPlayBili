package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/** Shared bounded executor for potentially blocking media resource closes. */
public final class MediaCloseExecutor {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MediaCloseExecutor() {
    }

    public static CompletableFuture<Void> closeAsync(AutoCloseable resource, String description) {
        return AsyncCloseExecutor.closeAsync(resource, description, LOGGER::warn);
    }
}