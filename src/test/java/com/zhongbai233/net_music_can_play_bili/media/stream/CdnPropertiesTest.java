package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdnPropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void effectiveMinimumsAndPreferredHostNormalizationRemainStable() {
        set(CdnProperties.SELECTOR_ENABLED, "false");
        set(CdnProperties.SELECTOR_RACE, "true");
        set(CdnProperties.RACE_BYTES, "0");
        set(CdnProperties.RACE_TIMEOUT_MILLIS, "100");
        set(CdnProperties.MAX_RACE_CANDIDATES, "0");
        set(CdnProperties.MIN_PERSIST_INTERVAL_MILLIS, "-1");
        set(CdnProperties.BACKGROUND_RACE_INTERVAL_MILLIS, "10");
        set(CdnProperties.PREFERRED_HOST, " CDN.Example.COM ");
        set(CdnProperties.FALLBACK_MAX_GROUPS, "0");

        CdnProperties.Selector selector = CdnProperties.selector();
        assertFalse(selector.enabled());
        assertTrue(selector.raceEnabled());
        assertEquals(1, selector.raceBytes());
        assertEquals(250L, selector.raceTimeoutMillis());
        assertEquals(1, selector.maxRaceCandidates());
        assertEquals(0L, selector.minPersistIntervalMillis());
        assertEquals(1_000L, selector.backgroundRaceIntervalMillis());
        assertEquals("cdn.example.com", selector.preferredHost());
        assertEquals(1, CdnProperties.fallback().maxGroups());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
