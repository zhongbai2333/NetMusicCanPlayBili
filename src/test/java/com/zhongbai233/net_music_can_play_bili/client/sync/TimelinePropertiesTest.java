package com.zhongbai233.net_music_can_play_bili.client.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelinePropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        assertEquals(new TimelineProperties.Clock(1_500L, 80L, 0.12D), TimelineProperties.clock());
        assertEquals(new TimelineProperties.Turntable(true, 2_000L, 500L,
                TimeUnit.MILLISECONDS.toNanos(30_000L), 500L, 20L, 0.20D),
                TimelineProperties.turntable());
        assertEquals(new TimelineProperties.Handheld(2_000L, 500L), TimelineProperties.handheld());
    }

    @Test
    void sharedClockUsesCanonicalThenLegacyThenDeprecatedKeys() {
        set(TimelineProperties.DEPRECATED_TURNTABLE_HARD_SYNC_MILLIS, "1600");
        set(TimelineProperties.DEPRECATED_TURNTABLE_MAX_SMOOTH_CORRECTION_MILLIS, "90");
        set(TimelineProperties.LEGACY_SMOOTH_CORRECTION_RATIO, "0.15");
        assertEquals(new TimelineProperties.Clock(1_600L, 90L, 0.15D), TimelineProperties.clock());

        set(TimelineProperties.LEGACY_HARD_SYNC_MILLIS, "1700");
        set(TimelineProperties.LEGACY_MAX_SMOOTH_CORRECTION_MILLIS, "100");
        assertEquals(new TimelineProperties.Clock(1_700L, 100L, 0.15D), TimelineProperties.clock());

        set(TimelineProperties.HARD_SYNC_MILLIS, "1800");
        set(TimelineProperties.MAX_SMOOTH_CORRECTION_MILLIS, "110");
        set(TimelineProperties.SMOOTH_CORRECTION_RATIO, "0.25");
        assertEquals(new TimelineProperties.Clock(1_800L, 110L, 0.25D), TimelineProperties.clock());
    }

    @Test
    void turntableValuesRemainConfigurable() {
        set(TimelineProperties.TURNTABLE_AUDIO_ANCHOR, "false");
        set(TimelineProperties.TURNTABLE_AUDIO_ANCHOR_MAX_LAG_MILLIS, "2500");
        set(TimelineProperties.TURNTABLE_AUDIO_ANCHOR_MAX_LEAD_MILLIS, "750");
        set(TimelineProperties.TURNTABLE_CLOCK_PRUNE_INTERVAL_MILLIS, "45000");
        set(TimelineProperties.TURNTABLE_VISUAL_HARD_SYNC_MILLIS, "600");
        set(TimelineProperties.TURNTABLE_VISUAL_MAX_CORRECTION_MILLIS, "30");
        set(TimelineProperties.TURNTABLE_VISUAL_CORRECTION_RATIO, "0.3");

        TimelineProperties.Turntable properties = TimelineProperties.turntable();
        assertFalse(properties.audioAnchored());
        assertEquals(2_500L, properties.audioAnchorMaxLagMillis());
        assertEquals(750L, properties.audioAnchorMaxLeadMillis());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(45_000L), properties.clockPruneIntervalNanos());
        assertEquals(600L, properties.visualHardSyncMillis());
        assertEquals(30L, properties.visualMaxCorrectionMillis());
        assertEquals(0.3D, properties.visualCorrectionRatio());
    }

    @Test
    void handheldAnchorLimitsRemainConfigurable() {
        set(TimelineProperties.HANDHELD_AUDIO_ANCHOR_MAX_LAG_MILLIS, "3000");
        set(TimelineProperties.HANDHELD_AUDIO_ANCHOR_MAX_LEAD_MILLIS, "900");

        assertEquals(new TimelineProperties.Handheld(3_000L, 900L), TimelineProperties.handheld());
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
