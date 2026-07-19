package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlaybackSyncTest {
    @Test
    void preservesMinecartMetadataAcrossTransfer() {
        UUID minecartUuid = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        String synced = PlaybackSync.withSync("https://example.invalid/audio.m4a", "session-1", 1_250L, 9_000L);
        synced = PlaybackSync.withMinecartAnchor(synced, 42, minecartUuid);

        PlaybackSync.Metadata metadata = PlaybackSync.parse(synced);
        PlaybackSync.MinecartAnchor anchor = PlaybackSync.parseMinecartAnchor(synced);
        assertEquals("session-1", metadata.sessionId());
        assertEquals(1_250L, metadata.elapsedMillis());
        assertEquals(9_000L, metadata.totalMillis());
        assertNotNull(anchor);
        assertEquals(42, anchor.entityId());
        assertEquals(minecartUuid, anchor.entityUuid());

        String transferred = PlaybackSync.transferSync(synced, "https://cdn.example.invalid/audio.m4a");
        PlaybackSync.MinecartAnchor transferredAnchor = PlaybackSync.parseMinecartAnchor(transferred);
        assertNotNull(transferredAnchor);
        assertEquals(42, transferredAnchor.entityId());
        assertEquals(minecartUuid, transferredAnchor.entityUuid());
        assertEquals("https://cdn.example.invalid/audio.m4a", PlaybackSync.strip(transferred));
    }
}