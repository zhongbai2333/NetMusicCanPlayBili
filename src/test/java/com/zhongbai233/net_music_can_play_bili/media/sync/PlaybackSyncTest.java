package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackSyncTest {
    @Test
    void preservesMinecartMetadataAcrossTransfer() {
        UUID minecartUuid = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        PlaybackSourceId sourceId = PlaybackSourceId.of(
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        String synced = PlaybackSync.withSync("https://example.invalid/audio.m4a", "session-1", 1_250L, 9_000L);
        synced = PlaybackSync.withSourceId(synced, sourceId);
        synced = PlaybackSync.withMinecartAnchor(synced, 42, minecartUuid);

        PlaybackSync.Metadata metadata = PlaybackSync.parse(synced);
        PlaybackSync.MinecartAnchor anchor = PlaybackSync.parseMinecartAnchor(synced);
        assertEquals("session-1", metadata.sessionId());
        assertEquals(PlaybackSessionId.of("session-1"), metadata.playbackSessionId().orElseThrow());
        assertEquals(1_250L, metadata.elapsedMillis());
        assertEquals(9_000L, metadata.totalMillis());
        assertNotNull(anchor);
        assertEquals(42, anchor.entityId());
        assertEquals(minecartUuid, anchor.entityUuid());
        assertEquals(sourceId, PlaybackSync.parsePlaybackSourceId(synced).orElseThrow());

        String transferred = PlaybackSync.transferSync(synced, "https://cdn.example.invalid/audio.m4a");
        PlaybackSync.MinecartAnchor transferredAnchor = PlaybackSync.parseMinecartAnchor(transferred);
        assertNotNull(transferredAnchor);
        assertEquals(42, transferredAnchor.entityId());
        assertEquals(minecartUuid, transferredAnchor.entityUuid());
        assertEquals(sourceId, PlaybackSync.parsePlaybackSourceId(transferred).orElseThrow());
        assertEquals("https://cdn.example.invalid/audio.m4a", PlaybackSync.strip(transferred));
    }

    @Test
    void requestTokenRoundTripsAlongsidePlaybackMetadata() {
        UUID minecartUuid = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        String synced = PlaybackSync.withSync("https://example.invalid/audio.mp3", "session-2", 70_000L,
                180_000L);
        synced = PlaybackSync.withMinecartAnchor(synced, 7, minecartUuid);
        MediaRequestToken token = new MediaRequestToken("request-123");
        String tokenized = PlaybackSync.withRequestToken(synced, token);

        assertEquals("request-123", PlaybackSync.parseRequestToken(tokenized));
        assertEquals(token, PlaybackSync.parseMediaRequestToken(tokenized).orElseThrow());
        assertEquals("session-2", PlaybackSync.parse(tokenized).sessionId());
        assertEquals(PlaybackSessionId.of("session-2"),
                PlaybackSync.parsePlaybackSessionId(tokenized).orElseThrow());
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
    void ignoresMissingOrInvalidFragmentIdentities() {
        String url = "https://example.invalid/audio.mp3";

        assertEquals(url, PlaybackSync.withSync(url, "session&other=1", 1_000L));
        assertEquals(url, PlaybackSync.withRequestToken(url, ""));
        assertEquals(url, PlaybackSync.withRequestToken(url, "request&other"));
        assertEquals("", PlaybackSync.parseRequestToken(url));
        assertEquals("", PlaybackSync.parseRequestToken(null));
        assertEquals("", PlaybackSync.parseRequestToken(url + "#nmb_request=request=other"));
        assertTrue(PlaybackSync.parsePlaybackSessionId(
                url + "#nmb_session=session=other&nmb_elapsed_ms=1000").isEmpty());
    }
}
