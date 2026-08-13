package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaClosePropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        assertEquals(new MediaCloseProperties.ExecutorConfig(2, 32), MediaCloseProperties.executor());
        assertEquals(new MediaCloseProperties.Timeouts(
                TimeUnit.MILLISECONDS.toNanos(500L), TimeUnit.MILLISECONDS.toNanos(3_000L)),
                MediaCloseProperties.openAlTimeouts());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(500L), MediaCloseProperties.openAlRetryNanos());
        assertEquals(new MediaCloseProperties.Timeouts(
                TimeUnit.MILLISECONDS.toNanos(3_000L), TimeUnit.MILLISECONDS.toNanos(6_000L)),
                MediaCloseProperties.videoTimeouts());
    }

    @Test
    void explicitValuesRemainConfigurable() {
        set(MediaCloseProperties.EXECUTOR_THREADS, "4");
        set(MediaCloseProperties.EXECUTOR_QUEUE_CAPACITY, "64");
        set(MediaCloseProperties.OPENAL_SOFT_TIMEOUT_MILLIS, "750");
        set(MediaCloseProperties.OPENAL_HARD_TIMEOUT_MILLIS, "4000");
        set(MediaCloseProperties.OPENAL_RETRY_MILLIS, "250");
        set(MediaCloseProperties.VIDEO_SOFT_TIMEOUT_MILLIS, "3500");
        set(MediaCloseProperties.VIDEO_HARD_TIMEOUT_MILLIS, "7000");

        assertEquals(new MediaCloseProperties.ExecutorConfig(4, 64), MediaCloseProperties.executor());
        assertEquals(new MediaCloseProperties.Timeouts(
                TimeUnit.MILLISECONDS.toNanos(750L), TimeUnit.MILLISECONDS.toNanos(4_000L)),
                MediaCloseProperties.openAlTimeouts());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(250L), MediaCloseProperties.openAlRetryNanos());
        assertEquals(new MediaCloseProperties.Timeouts(
                TimeUnit.MILLISECONDS.toNanos(3_500L), TimeUnit.MILLISECONDS.toNanos(7_000L)),
                MediaCloseProperties.videoTimeouts());
    }

    @Test
    void invalidValuesUseDefaults() {
        set(MediaCloseProperties.EXECUTOR_THREADS, "invalid");
        set(MediaCloseProperties.OPENAL_SOFT_TIMEOUT_MILLIS, "invalid");
        set(MediaCloseProperties.OPENAL_RETRY_MILLIS, "invalid");
        set(MediaCloseProperties.VIDEO_HARD_TIMEOUT_MILLIS, "invalid");

        assertEquals(2, MediaCloseProperties.executor().threads());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(500L),
                MediaCloseProperties.openAlTimeouts().softTimeoutNanos());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(500L), MediaCloseProperties.openAlRetryNanos());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(6_000L),
                MediaCloseProperties.videoTimeouts().hardTimeoutNanos());
    }

    @Test
    void unsafeValuesAreClampedAndLargeDurationsSaturate() {
        set(MediaCloseProperties.EXECUTOR_THREADS, "0");
        set(MediaCloseProperties.EXECUTOR_QUEUE_CAPACITY, "-1");
        set(MediaCloseProperties.OPENAL_SOFT_TIMEOUT_MILLIS, "5000");
        set(MediaCloseProperties.OPENAL_HARD_TIMEOUT_MILLIS, "1000");
        set(MediaCloseProperties.OPENAL_RETRY_MILLIS, "-1");
        set(MediaCloseProperties.VIDEO_SOFT_TIMEOUT_MILLIS, Long.toString(Long.MAX_VALUE));
        set(MediaCloseProperties.VIDEO_HARD_TIMEOUT_MILLIS, Long.toString(Long.MAX_VALUE));

        assertEquals(new MediaCloseProperties.ExecutorConfig(1, 1), MediaCloseProperties.executor());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(5_000L),
                MediaCloseProperties.openAlTimeouts().hardTimeoutNanos());
        assertEquals(1L, MediaCloseProperties.openAlRetryNanos());
        assertEquals(Long.MAX_VALUE, MediaCloseProperties.videoTimeouts().softTimeoutNanos());
        assertEquals(Long.MAX_VALUE, MediaCloseProperties.videoTimeouts().hardTimeoutNanos());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
