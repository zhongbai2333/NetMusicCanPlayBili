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
    void defaultsRemainCompatible() {
        assertEquals(new PlaybackRuntimeProperties.Watchdog(900, 300, 240, 2_000L),
                PlaybackRuntimeProperties.watchdog());
        assertEquals(0L, PlaybackRuntimeProperties.audioSyncAheadToleranceTicks());
        assertEquals(new PlaybackRuntimeProperties.Diagnostics(2_000L, 250L),
                PlaybackRuntimeProperties.diagnostics());
    }

    @Test
    void explicitValuesRemainConfigurable() {
        set(PlaybackRuntimeProperties.LIVE_WATCHDOG_STALL_MILLIS, "60000");
        set(PlaybackRuntimeProperties.AUDIO_WATCHDOG_STARTUP_STALL_MILLIS, "20000");
        set(PlaybackRuntimeProperties.AUDIO_WATCHDOG_NO_PROGRESS_MILLIS, "10000");
        set(PlaybackRuntimeProperties.AUDIO_WATCHDOG_END_GRACE_MILLIS, "3000");
        set(PlaybackRuntimeProperties.AUDIO_SYNC_AHEAD_TOLERANCE_TICKS, "4");
        set(PlaybackRuntimeProperties.WARN_DRIFT_MILLIS, "1500");
        set(PlaybackRuntimeProperties.DEBUG_AV_DRIFT_MILLIS, "180");

        assertEquals(new PlaybackRuntimeProperties.Watchdog(1_200, 400, 200, 3_000L),
                PlaybackRuntimeProperties.watchdog());
        assertEquals(4L, PlaybackRuntimeProperties.audioSyncAheadToleranceTicks());
        assertEquals(new PlaybackRuntimeProperties.Diagnostics(1_500L, 180L),
                PlaybackRuntimeProperties.diagnostics());
    }

    @Test
    void canonicalKeysTakePriorityOverLegacyFallbacks() {
        set(PlaybackRuntimeProperties.LEGACY_AUDIO_SYNC_AHEAD_TOLERANCE_TICKS, "3");
        set(PlaybackRuntimeProperties.LEGACY_DEBUG_AV_DRIFT_MILLIS, "300");
        assertEquals(3L, PlaybackRuntimeProperties.audioSyncAheadToleranceTicks());
        assertEquals(300L, PlaybackRuntimeProperties.diagnostics().debugAvDriftMillis());

        set(PlaybackRuntimeProperties.AUDIO_SYNC_AHEAD_TOLERANCE_TICKS, "5");
        set(PlaybackRuntimeProperties.DEBUG_AV_DRIFT_MILLIS, "200");
        assertEquals(5L, PlaybackRuntimeProperties.audioSyncAheadToleranceTicks());
        assertEquals(200L, PlaybackRuntimeProperties.diagnostics().debugAvDriftMillis());
    }

    @Test
    void invalidValuesUseDefaultsOrValidLegacyFallbacks() {
        set(PlaybackRuntimeProperties.LIVE_WATCHDOG_STALL_MILLIS, "invalid");
        set(PlaybackRuntimeProperties.AUDIO_WATCHDOG_STARTUP_STALL_MILLIS, "invalid");
        set(PlaybackRuntimeProperties.AUDIO_WATCHDOG_NO_PROGRESS_MILLIS, "invalid");
        set(PlaybackRuntimeProperties.AUDIO_WATCHDOG_END_GRACE_MILLIS, "invalid");
        set(PlaybackRuntimeProperties.AUDIO_SYNC_AHEAD_TOLERANCE_TICKS, "invalid");
        set(PlaybackRuntimeProperties.LEGACY_AUDIO_SYNC_AHEAD_TOLERANCE_TICKS, "6");
        set(PlaybackRuntimeProperties.WARN_DRIFT_MILLIS, "invalid");
        set(PlaybackRuntimeProperties.DEBUG_AV_DRIFT_MILLIS, "invalid");
        set(PlaybackRuntimeProperties.LEGACY_DEBUG_AV_DRIFT_MILLIS, "220");

        assertEquals(new PlaybackRuntimeProperties.Watchdog(900, 300, 240, 2_000L),
                PlaybackRuntimeProperties.watchdog());
        assertEquals(6L, PlaybackRuntimeProperties.audioSyncAheadToleranceTicks());
        assertEquals(new PlaybackRuntimeProperties.Diagnostics(2_000L, 220L),
                PlaybackRuntimeProperties.diagnostics());
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
