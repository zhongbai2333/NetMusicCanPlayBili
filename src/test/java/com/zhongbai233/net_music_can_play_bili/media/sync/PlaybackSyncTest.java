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

    @Test
    void requestTokenRoundTripsAlongsidePlaybackMetadata() {
        UUID minecartUuid = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        String synced = PlaybackSync.withSync("https://example.invalid/audio.mp3", "session-2", 70_000L,
                180_000L);
        synced = PlaybackSync.withMinecartAnchor(synced, 7, minecartUuid);
        String tokenized = PlaybackSync.withRequestToken(synced, "request-123");

        assertEquals("request-123", PlaybackSync.parseRequestToken(tokenized));
        assertEquals("session-2", PlaybackSync.parse(tokenized).sessionId());
        assertNotNull(PlaybackSync.parseMinecartAnchor(tokenized));
        assertEquals("https://example.invalid/audio.mp3", PlaybackSync.strip(tokenized));
    }

    @Test
    void requestTokenIsOneShotMetadataAndIsNotTransferredToAnotherMediaUrl() {
        String source = PlaybackSync.withSync("https://example.invalid/source.mp3", "session-3", 5_000L,
                60_000L);
        source = PlaybackSync.withRequestToken(source, "do-not-transfer");

        String transferred = PlaybackSync.transferSync(source, "https://cdn.example.invalid/target.mp3");

        assertEquals("", PlaybackSync.parseRequestToken(transferred));
        assertEquals("session-3", PlaybackSync.parse(transferred).sessionId());
        assertEquals("https://cdn.example.invalid/target.mp3", PlaybackSync.strip(transferred));
    }

    @Test
    void ignoresMissingOrBlankRequestTokens() {
        String url = "https://example.invalid/audio.mp3";

        assertEquals(url, PlaybackSync.withRequestToken(url, ""));
        assertEquals("", PlaybackSync.parseRequestToken(url));
        assertEquals("", PlaybackSync.parseRequestToken(null));
    }
}