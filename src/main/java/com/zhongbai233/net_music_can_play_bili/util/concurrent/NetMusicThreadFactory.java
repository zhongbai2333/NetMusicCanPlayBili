package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared daemon thread factory for background media/client workers. */
public final class NetMusicThreadFactory implements ThreadFactory {
    private static final System.Logger LOGGER = System.getLogger(NetMusicThreadFactory.class.getName());
    private static final Thread.UncaughtExceptionHandler UNCAUGHT_EXCEPTION_HANDLER = (thread, error) ->
            LOGGER.log(System.Logger.Level.ERROR, "后台线程 " + thread.getName() + " 未捕获异常", error);
    private final String prefix;
    private final AtomicInteger next = new AtomicInteger(1);

    private NetMusicThreadFactory(String prefix) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    public static NetMusicThreadFactory daemon(String prefix) {
        return new NetMusicThreadFactory(prefix);
    }

    public static Thread daemonThread(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler(UNCAUGHT_EXCEPTION_HANDLER);
        return thread;
    }

    public static Thread daemonThread(String name, Runnable runnable, int priority) {
        Thread thread = daemonThread(name, runnable);
        thread.setPriority(Math.clamp(priority, Thread.MIN_PRIORITY, Thread.MAX_PRIORITY));
        return thread;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        return daemonThread(prefix + "-" + next.getAndIncrement(), runnable);
    }
}
