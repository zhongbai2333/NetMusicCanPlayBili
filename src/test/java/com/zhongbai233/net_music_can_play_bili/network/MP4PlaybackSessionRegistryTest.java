package com.zhongbai233.net_music_can_play_bili.network;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MP4PlaybackSessionRegistryTest {
    @Test
    void replacementClearsThePreviousMissingSourceGracePeriod() {
        MP4PlaybackSessionRegistry<UUID, Session> registry = new MP4PlaybackSessionRegistry<>();
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
        MP4PlaybackSessionRegistry<UUID, Session> registry = new MP4PlaybackSessionRegistry<>();
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
        MP4PlaybackSessionRegistry<UUID, Session> registry = new MP4PlaybackSessionRegistry<>();
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
        MP4PlaybackSessionRegistry<UUID, Session> registry = new MP4PlaybackSessionRegistry<>();
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
