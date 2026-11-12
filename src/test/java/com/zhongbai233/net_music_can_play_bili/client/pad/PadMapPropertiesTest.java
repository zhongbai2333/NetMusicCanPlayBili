package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadMapPropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void cacheDefaultsAndExistingMinimumsRemainStable() {
        PadMapProperties.Cache defaults = PadMapProperties.cache();
        assertEquals(24, defaults.chunksPerTick());
        assertEquals(1.25F, defaults.outdoorZoom());
        assertEquals(3.0F, defaults.indoorZoom());
        assertEquals(2.0F, defaults.indoorDisplayScale());
        assertTrue(defaults.diskCacheEnabled());

        set(PadMapProperties.DIRTY_CHUNKS_PER_TICK, "0");
        set(PadMapProperties.UPDATE_INTERVAL_TICKS, "-2");
        set(PadMapProperties.UNKNOWN_RETRY_TICKS, "5");
        set(PadMapProperties.OUTDOOR_ZOOM, "NaN");
        set(PadMapProperties.INDOOR_ZOOM, "Infinity");
        set(PadMapProperties.INDOOR_DISPLAY_SCALE, "invalid");
        set(PadMapProperties.DISK_CACHE, "invalid");

        PadMapProperties.Cache configured = PadMapProperties.cache();
        assertEquals(1, configured.dirtyChunksPerTick());
        assertEquals(1, configured.updateIntervalTicks());
        assertEquals(20, configured.unknownRetryTicks());
        assertEquals(1.25F, configured.outdoorZoom());
        assertEquals(3.0F, configured.indoorZoom());
        assertEquals(2.0F, configured.indoorDisplayScale());
        assertTrue(configured.diskCacheEnabled());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
