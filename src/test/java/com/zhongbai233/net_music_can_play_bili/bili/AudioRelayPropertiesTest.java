package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioRelayPropertiesTest {
    @AfterEach
    void clearProperties() {
        System.clearProperty(AudioRelayProperties.MUTE_MAIN_WHEN_CONNECTED);
        System.clearProperty(AudioRelayProperties.LEGACY_MUTE_MAIN_WHEN_STARTED);
    }

    @Test
    void defaultsToMutedForCompatibility() {
        assertTrue(AudioRelayProperties.muteMainWhenConnected());
    }

    @Test
    void currentKeyTakesPrecedenceOverLegacyKey() {
        System.setProperty(AudioRelayProperties.MUTE_MAIN_WHEN_CONNECTED, "false");
        System.setProperty(AudioRelayProperties.LEGACY_MUTE_MAIN_WHEN_STARTED, "true");
        assertFalse(AudioRelayProperties.muteMainWhenConnected());
    }

    @Test
    void invalidCurrentValueFallsBackToLegacyKey() {
        System.setProperty(AudioRelayProperties.MUTE_MAIN_WHEN_CONNECTED, "invalid");
        System.setProperty(AudioRelayProperties.LEGACY_MUTE_MAIN_WHEN_STARTED, "false");
        assertFalse(AudioRelayProperties.muteMainWhenConnected());
    }
}
