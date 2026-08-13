package com.zhongbai233.net_music_can_play_bili.media.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAlHrtfPropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        assertEquals(new OpenAlHrtfProperties.Settings(false, false), OpenAlHrtfProperties.settings());
    }

    @Test
    void legacyCamelCaseKeysRemainSupported() {
        set(OpenAlHrtfProperties.LEGACY_FORCE_HRTF, "true");
        set(OpenAlHrtfProperties.LEGACY_FORCE_HRTF_WITH_CHANNEL, "true");

        assertEquals(new OpenAlHrtfProperties.Settings(true, true), OpenAlHrtfProperties.settings());
    }

    @Test
    void canonicalKeysTakePriorityOverLegacyKeys() {
        set(OpenAlHrtfProperties.LEGACY_FORCE_HRTF, "true");
        set(OpenAlHrtfProperties.LEGACY_FORCE_HRTF_WITH_CHANNEL, "true");
        set(OpenAlHrtfProperties.FORCE_HRTF, "false");
        set(OpenAlHrtfProperties.FORCE_HRTF_WITH_CHANNEL, "false");

        assertEquals(new OpenAlHrtfProperties.Settings(false, false), OpenAlHrtfProperties.settings());
    }

    @Test
    void disableOverrideWinsOverForceOverride() {
        set(OpenAlHrtfProperties.FORCE_HRTF, "true");
        set(OpenAlHrtfProperties.DISABLE_HRTF, "true");
        set(OpenAlHrtfProperties.FORCE_HRTF_WITH_CHANNEL, "true");

        assertEquals(new OpenAlHrtfProperties.Settings(false, true), OpenAlHrtfProperties.settings());
    }

    @Test
    void invalidCanonicalValuesUseLegacyOrDefaultValues() {
        set(OpenAlHrtfProperties.FORCE_HRTF, "yes");
        set(OpenAlHrtfProperties.LEGACY_FORCE_HRTF, "true");
        set(OpenAlHrtfProperties.DISABLE_HRTF, "no");
        set(OpenAlHrtfProperties.LEGACY_DISABLE_HRTF, "false");
        set(OpenAlHrtfProperties.FORCE_HRTF_WITH_CHANNEL, "1");

        assertEquals(new OpenAlHrtfProperties.Settings(true, false), OpenAlHrtfProperties.settings());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
