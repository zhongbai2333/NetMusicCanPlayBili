package com.zhongbai233.net_music_can_play_bili.client.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybackRuntimePropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void unsafeDurationsAreClamped() {
        set(PlaybackRuntimeProperties.LIVE_WATCHDOG_STALL_MILLIS, "0");
        set(PlaybackRuntimeProperties.AUDIO_WATCHDOG_STARTUP_STALL_MILLIS, "-1");
        set(PlaybackRuntimeProperties.AUDIO_WATCHDOG_NO_PROGRESS_MILLIS, "0");
        set(PlaybackRuntimeProperties.AUDIO_WATCHDOG_END_GRACE_MILLIS, "-1");
        set(PlaybackRuntimeProperties.WARN_DRIFT_MILLIS, "-1");
        set(PlaybackRuntimeProperties.DEBUG_AV_DRIFT_MILLIS, "-1");

        assertEquals(new PlaybackRuntimeProperties.Watchdog(100, 20, 20, 0L),
                PlaybackRuntimeProperties.watchdog());
        assertEquals(new PlaybackRuntimeProperties.Diagnostics(0L, 0L),
                PlaybackRuntimeProperties.diagnostics());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
