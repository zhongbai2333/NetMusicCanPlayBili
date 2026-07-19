package com.zhongbai233.net_music_can_play_bili.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlaybackSourceRegistryTest {
    @Test
    void storesSnapshotsAndPrunesSources() {
        PlaybackSourceRegistry<Integer> registry = new PlaybackSourceRegistry<>();
        registry.put("old", 1);
        registry.put("current", 2);

        registry.removeIf(value -> value < 2);

        assertNull(registry.get("old"));
        assertEquals(2, registry.get("current"));
        assertEquals(1, registry.snapshot().size());
        assertEquals(java.util.Set.of("current"), registry.keys());
    }
}