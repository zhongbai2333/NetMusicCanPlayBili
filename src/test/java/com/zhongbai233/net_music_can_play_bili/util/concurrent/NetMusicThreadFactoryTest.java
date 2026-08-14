package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetMusicThreadFactoryTest {
    @Test
    void createsNamedDaemonThreads() {
        Thread direct = NetMusicThreadFactory.daemonThread("unit-worker", () -> {
        });
        assertTrue(direct.isDaemon());
        assertEquals("unit-worker", direct.getName());

        Thread pooled = NetMusicThreadFactory.daemon("unit-pool").newThread(() -> {
        });
        assertTrue(pooled.isDaemon());
        assertTrue(pooled.getName().startsWith("unit-pool-"));
        assertTrue(direct.getUncaughtExceptionHandler() != null);
        assertTrue(pooled.getUncaughtExceptionHandler() != null);

        Thread lowPriority = NetMusicThreadFactory.daemonThread("unit-low-priority", () -> {
        }, Thread.NORM_PRIORITY - 1);
        assertEquals(Thread.NORM_PRIORITY - 1, lowPriority.getPriority());
        assertTrue(lowPriority.isDaemon());
    }
}
