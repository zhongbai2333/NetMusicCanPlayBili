package com.zhongbai233.net_music_can_play_bili.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaConsumerRegistryTest {
    @Test
    void deduplicatesMovesAndRemovesConsumers() {
        MediaConsumerRegistry<String> registry = new MediaConsumerRegistry<>();
        registry.register("live-a", "console-1");
        registry.register("live-a", "console-1");
        registry.register("live-a", "console-2");
        assertEquals(2, registry.consumersFor("live-a").size());

        registry.register("live-b", "console-1");
        assertEquals(java.util.List.of("console-2"), registry.consumersFor("live-a"));
        assertEquals(java.util.List.of("console-1"), registry.consumersFor("live-b"));

        registry.unregister("console-1");
        assertTrue(registry.consumersFor("live-b").isEmpty());
        registry.clear();
        assertTrue(registry.consumersFor("live-a").isEmpty());
    }
}