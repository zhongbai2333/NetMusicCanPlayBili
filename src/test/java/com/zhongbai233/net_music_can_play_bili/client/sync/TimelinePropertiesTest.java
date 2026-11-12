package com.zhongbai233.net_music_can_play_bili.client.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelinePropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void invalidAndUnsafeValuesFallBackOrClamp() {
        set(TimelineProperties.HARD_SYNC_MILLIS, "invalid");
        set(TimelineProperties.LEGACY_HARD_SYNC_MILLIS, "-1");
        set(TimelineProperties.SMOOTH_CORRECTION_RATIO, "NaN");
        set(TimelineProperties.TURNTABLE_AUDIO_ANCHOR, "yes");
        set(TimelineProperties.TURNTABLE_AUDIO_ANCHOR_MAX_LAG_MILLIS, "-1");
        set(TimelineProperties.TURNTABLE_CLOCK_PRUNE_INTERVAL_MILLIS, Long.toString(Long.MAX_VALUE));
        set(TimelineProperties.TURNTABLE_VISUAL_CORRECTION_RATIO, "2.0");
        set(TimelineProperties.HANDHELD_AUDIO_ANCHOR_MAX_LEAD_MILLIS, "-1");

        assertEquals(new TimelineProperties.Clock(0L, 80L, 0.12D), TimelineProperties.clock());
        TimelineProperties.Turntable turntable = TimelineProperties.turntable();
        assertTrue(turntable.audioAnchored());
        assertEquals(0L, turntable.audioAnchorMaxLagMillis());
        assertEquals(Long.MAX_VALUE, turntable.clockPruneIntervalNanos());
        assertEquals(1.0D, turntable.visualCorrectionRatio());
        assertEquals(0L, TimelineProperties.handheld().audioAnchorMaxLeadMillis());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
