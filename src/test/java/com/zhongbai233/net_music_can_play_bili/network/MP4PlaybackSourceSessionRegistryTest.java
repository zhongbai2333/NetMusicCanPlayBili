package com.zhongbai233.net_music_can_play_bili.network;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MP4PlaybackSourceSessionRegistryTest {
    private static final UUID SOURCE_ID = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void preservesTheUuidFacadeAcrossReplacementAndSnapshots() {
        MP4PlaybackSourceSessionRegistry<Session> registry = new MP4PlaybackSourceSessionRegistry<>();
        Session first = new Session("first");
        Session replacement = new Session("replacement");

        assertNull(registry.replace(SOURCE_ID, first));
        assertSame(first, registry.get(SOURCE_ID));
        assertEquals(List.of(Map.entry(SOURCE_ID, first)), registry.entries());

        assertTrue(registry.replace(SOURCE_ID, first, replacement));
        assertSame(replacement, registry.get(SOURCE_ID));
        assertFalse(registry.replace(SOURCE_ID, first, first));
    }

    @Test
    void missingSourceGraceRemainsBoundToTheExactSession() {
        MP4PlaybackSourceSessionRegistry<Session> registry = new MP4PlaybackSourceSessionRegistry<>();
        Session first = new Session("first");
        Session replacement = new Session("replacement");
        registry.replace(SOURCE_ID, first);

        assertEquals(20L, registry.markMissingIfCurrent(SOURCE_ID, first, 20L));
        registry.replace(SOURCE_ID, replacement);

        assertNull(registry.markMissingIfCurrent(SOURCE_ID, first, 30L));
        assertEquals(30L, registry.markMissingIfCurrent(SOURCE_ID, replacement, 30L));
        assertTrue(registry.clearMissingIfCurrent(SOURCE_ID, replacement));
    }

    @Test
    void nullableLookupAndExactRemovalKeepTheExistingFacadeContract() {
        MP4PlaybackSourceSessionRegistry<Session> registry = new MP4PlaybackSourceSessionRegistry<>();
        Session session = new Session("session");

        assertNull(registry.get(null));
        assertFalse(registry.contains(null));
        assertFalse(registry.remove(null, session));

        registry.replace(SOURCE_ID, session);
        assertTrue(registry.remove(SOURCE_ID, session));
        assertTrue(registry.isEmpty());
    }

    private record Session(String id) {
    }
}
