package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class AsyncCloseExecutorTest {
    @Test
    void closesMediaResourcesOffCallerThread() {
        String callerThread = Thread.currentThread().getName();
        AtomicReference<String> closeThread = new AtomicReference<>();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> AsyncCloseExecutor.closeAsync(
                () -> closeThread.set(Thread.currentThread().getName()), "test resource", ignored -> {
                }).join());

        assertNotNull(closeThread.get());
        assertNotEquals(callerThread, closeThread.get());
    }
}