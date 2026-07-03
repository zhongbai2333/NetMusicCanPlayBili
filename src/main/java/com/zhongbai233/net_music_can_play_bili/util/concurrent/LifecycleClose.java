package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import java.io.Closeable;
import java.io.IOException;

/** Small lifecycle helpers shared by background media workers. */
public final class LifecycleClose {
    private static final long DEFAULT_JOIN_TIMEOUT_MILLIS = 2_000L;

    private LifecycleClose() {
    }

    public static void interruptAndJoin(Thread thread) {
        interruptAndJoin(thread, DEFAULT_JOIN_TIMEOUT_MILLIS);
    }

    public static void interruptAndJoin(Thread thread, long timeoutMillis) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        thread.interrupt();
        join(thread, timeoutMillis);
    }

    public static void join(Thread thread, long timeoutMillis) {
        if (thread == null || thread == Thread.currentThread() || !thread.isAlive()) {
            return;
        }
        try {
            thread.join(Math.max(0L, timeoutMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
