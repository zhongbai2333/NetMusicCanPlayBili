package com.zhongbai233.net_music_can_play_bili.client.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientMediaPreparePropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        assertEquals(new ClientMediaPrepareProperties.Settings(2, 20L, 12L, 12L),
                ClientMediaPrepareProperties.settings());
    }

    @Test
    void explicitValuesRemainConfigurable() {
        set(ClientMediaPrepareProperties.AUDIO_PREPARE_THREADS, "4");
        set(ClientMediaPrepareProperties.MODERN_PREPARE_TIMEOUT_SECONDS, "30");
        set(ClientMediaPrepareProperties.MP4_PREPARE_TIMEOUT_SECONDS, "18");
        set(ClientMediaPrepareProperties.PAD_PREPARE_TIMEOUT_SECONDS, "24");

        assertEquals(new ClientMediaPrepareProperties.Settings(4, 30L, 18L, 24L),
                ClientMediaPrepareProperties.settings());
    }

    @Test
    void invalidValuesUseDefaults() {
        set(ClientMediaPrepareProperties.AUDIO_PREPARE_THREADS, "invalid");
        set(ClientMediaPrepareProperties.MODERN_PREPARE_TIMEOUT_SECONDS, "invalid");
        set(ClientMediaPrepareProperties.MP4_PREPARE_TIMEOUT_SECONDS, "invalid");
        set(ClientMediaPrepareProperties.PAD_PREPARE_TIMEOUT_SECONDS, "invalid");

        assertEquals(new ClientMediaPrepareProperties.Settings(2, 20L, 12L, 12L),
                ClientMediaPrepareProperties.settings());
    }

    @Test
    void unsafeValuesAreClamped() {
        set(ClientMediaPrepareProperties.AUDIO_PREPARE_THREADS, "0");
        set(ClientMediaPrepareProperties.MODERN_PREPARE_TIMEOUT_SECONDS, "0");
        set(ClientMediaPrepareProperties.MP4_PREPARE_TIMEOUT_SECONDS, "-1");
        set(ClientMediaPrepareProperties.PAD_PREPARE_TIMEOUT_SECONDS, "-10");

        assertEquals(new ClientMediaPrepareProperties.Settings(1, 3L, 3L, 3L),
                ClientMediaPrepareProperties.settings());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
