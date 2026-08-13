package com.zhongbai233.net_music_can_play_bili.client.renderer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientDisplayPropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        assertEquals(1_000L, ClientDisplayProperties.controlConsoleVideoHealthCheckMillis());
        assertTrue(ClientDisplayProperties.holographicWorldScreenEnabled());
        assertTrue(ClientDisplayProperties.mp4ProjectedInputEnabled());
    }

    @Test
    void explicitValuesRemainConfigurable() {
        set(ClientDisplayProperties.CONTROL_CONSOLE_VIDEO_HEALTH_CHECK_MILLIS, "250");
        set(ClientDisplayProperties.HOLOGRAPHIC_WORLD_SCREEN_ENABLED, "false");
        set(ClientDisplayProperties.MP4_PROJECTED_INPUT_ENABLED, "false");

        assertEquals(250L, ClientDisplayProperties.controlConsoleVideoHealthCheckMillis());
        assertFalse(ClientDisplayProperties.holographicWorldScreenEnabled());
        assertFalse(ClientDisplayProperties.mp4ProjectedInputEnabled());
    }

    @Test
    void invalidValuesUseCompatibleDefaults() {
        set(ClientDisplayProperties.CONTROL_CONSOLE_VIDEO_HEALTH_CHECK_MILLIS, "invalid");
        set(ClientDisplayProperties.HOLOGRAPHIC_WORLD_SCREEN_ENABLED, "enabled");
        set(ClientDisplayProperties.MP4_PROJECTED_INPUT_ENABLED, "disabled");

        assertEquals(1_000L, ClientDisplayProperties.controlConsoleVideoHealthCheckMillis());
        assertTrue(ClientDisplayProperties.holographicWorldScreenEnabled());
        assertTrue(ClientDisplayProperties.mp4ProjectedInputEnabled());
    }

    @Test
    void unsafeHealthCheckIntervalsAreClamped() {
        set(ClientDisplayProperties.CONTROL_CONSOLE_VIDEO_HEALTH_CHECK_MILLIS, "0");
        assertEquals(100L, ClientDisplayProperties.controlConsoleVideoHealthCheckMillis());

        set(ClientDisplayProperties.CONTROL_CONSOLE_VIDEO_HEALTH_CHECK_MILLIS, "-1");
        assertEquals(100L, ClientDisplayProperties.controlConsoleVideoHealthCheckMillis());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
