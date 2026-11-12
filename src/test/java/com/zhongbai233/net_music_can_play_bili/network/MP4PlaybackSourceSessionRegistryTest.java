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
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void replacementClearsThePreviousMissingSourceGracePeriod() {
        MP4PlaybackSourceSessionRegistry<Session> registry = new MP4PlaybackSourceSessionRegistry<>();
        UUID deviceId = UUID.randomUUID();
        Session first = new Session("first");
        Session replacement = new Session("first");

        registry.replace(deviceId, first);
        assertEquals(20L, registry.markMissingIfCurrent(deviceId, first, 20L));
        assertEquals(20L, registry.markMissingIfCurrent(deviceId, first, 35L));

        registry.replace(deviceId, replacement);

        assertNull(registry.markMissingIfCurrent(deviceId, first, 40L));
        assertEquals(40L, registry.markMissingIfCurrent(deviceId, replacement, 40L));
    }

    @Test
    void exactMutationCannotOverwriteOrRemoveAReplacement() {
        MP4PlaybackSourceSessionRegistry<Session> registry = new MP4PlaybackSourceSessionRegistry<>();
        UUID deviceId = UUID.randomUUID();
        Session first = new Session("first");
        Session replacement = new Session("first");
        Session staleUpdate = new Session("stale-update");

        registry.replace(deviceId, first);
        registry.replace(deviceId, replacement);

        assertFalse(registry.replace(deviceId, first, staleUpdate));
        assertFalse(registry.remove(deviceId, first));
        assertSame(replacement, registry.get(deviceId));
        assertTrue(registry.remove(deviceId, replacement));
        assertTrue(registry.isEmpty());
    }

    @Test
    void snapshotsAreImmutableAndStableAcrossLaterMutations() {
        MP4PlaybackSourceSessionRegistry<Session> registry = new MP4PlaybackSourceSessionRegistry<>();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Session first = new Session("first");
        Session second = new Session("second");
        registry.replace(firstId, first);

        List<Map.Entry<UUID, Session>> entries = registry.entries();
        List<Session> values = registry.values();
        registry.replace(firstId, second);
        registry.replace(secondId, new Session("third"));

        assertEquals(List.of(Map.entry(firstId, first)), entries);
        assertEquals(List.of(first), values);
        assertThrows(UnsupportedOperationException.class, entries::clear);
        assertThrows(UnsupportedOperationException.class, values::clear);
        assertEquals(2, registry.size());
    }

    @Test
    void clearRemovesSessionsAndTheirMissingSourceState() {
        MP4PlaybackSourceSessionRegistry<Session> registry = new MP4PlaybackSourceSessionRegistry<>();
        UUID deviceId = UUID.randomUUID();
        Session session = new Session("session");
        registry.replace(deviceId, session);
        registry.markMissingIfCurrent(deviceId, session, 12L);

        registry.clear();
        registry.replace(deviceId, session);

        assertEquals(30L, registry.markMissingIfCurrent(deviceId, session, 30L));
    }

    private record Session(String id) {
    }
}
