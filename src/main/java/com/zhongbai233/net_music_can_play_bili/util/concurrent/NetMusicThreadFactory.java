package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared daemon thread factory for background media/client workers. */
public final class NetMusicThreadFactory implements ThreadFactory {
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
        return thread;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        return daemonThread(prefix + "-" + next.getAndIncrement(), runnable);
    }
}
