package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioSyncPropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        assertEquals(new AudioSyncPolicy(8L, 28L, 40L, 8L, 12L), AudioSyncProperties.policy());
    }

    @Test
    void canonicalKeysOverrideLegacyAliases() {
        set(AudioSyncProperties.LEGACY_CATCH_UP_START_TICKS, "10");
        set(AudioSyncProperties.LEGACY_CATCH_UP_FULL_TICKS, "30");
        set(AudioSyncProperties.LEGACY_OUTPUT_LAG_FLUSH_TICKS, "50");
        set(AudioSyncProperties.LEGACY_FED_NEAR_TARGET_TICKS, "9");
        assertEquals(new AudioSyncPolicy(10L, 30L, 50L, 9L, 12L), AudioSyncProperties.policy());

        set(AudioSyncProperties.CATCH_UP_START_TICKS, "12");
        set(AudioSyncProperties.CATCH_UP_FULL_TICKS, "36");
        set(AudioSyncProperties.OUTPUT_LAG_FLUSH_TICKS, "60");
        set(AudioSyncProperties.FED_NEAR_TARGET_TICKS, "11");
        set(AudioSyncProperties.FLUSH_AHEAD_TICKS, "14");
        assertEquals(new AudioSyncPolicy(12L, 36L, 60L, 11L, 14L), AudioSyncProperties.policy());
    }

    @Test
    void invalidCanonicalValuesUseLegacyAndNegativeThresholdsAreClamped() {
        set(AudioSyncProperties.CATCH_UP_START_TICKS, "invalid");
        set(AudioSyncProperties.LEGACY_CATCH_UP_START_TICKS, "-1");
        set(AudioSyncProperties.CATCH_UP_FULL_TICKS, "-1");
        set(AudioSyncProperties.OUTPUT_LAG_FLUSH_TICKS, "-1");
        set(AudioSyncProperties.FED_NEAR_TARGET_TICKS, "-1");
        set(AudioSyncProperties.FLUSH_AHEAD_TICKS, "-1");

        assertEquals(new AudioSyncPolicy(0L, 1L, 0L, 0L, 0L), AudioSyncProperties.policy());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
