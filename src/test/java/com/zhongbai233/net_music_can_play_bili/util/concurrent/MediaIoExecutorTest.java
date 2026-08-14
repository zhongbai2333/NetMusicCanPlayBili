package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaIoExecutorTest {
    @Test
    void runsOnNamedDaemonOutsideCommonPool() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Thread worker = MediaIoExecutor.supply(Thread::currentThread).join();
            assertTrue(worker.isDaemon());
            assertTrue(worker.getName().startsWith("media-io-"));
        });
    }
}
