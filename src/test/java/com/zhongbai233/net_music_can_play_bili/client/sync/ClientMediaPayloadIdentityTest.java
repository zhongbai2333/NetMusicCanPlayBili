package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientMediaPayloadIdentityTest {
    @Test
    void syncPacketExposesTypedSessionWithoutChangingWireField() {
        UUID ownerId = UUID.randomUUID();
        ClientMediaSyncPayload packet = new TestSyncPayload(ownerId, ownerId,
                ClientMediaSyncPayload.SOURCE_PLAYER, -1, 0.0D, 0.0D, 0.0D,
                true, 0, "https://example.invalid/play", "https://example.invalid/raw", "song", 120,
                700, "sync-session", 1_000L, false);

        assertEquals("sync-session", packet.sessionId());
        assertEquals(Optional.of(PlaybackSessionId.of("sync-session")), packet.playbackSessionId());
    }

    @Test
    void timelinePacketRejectsInvalidTypedSessionAtRuntimeBoundary() {
        ClientMediaTimelinePayload packet = new TestTimelinePayload(UUID.randomUUID(), "bad=session",
                1_000L, 700, false);

        assertEquals("bad=session", packet.sessionId());
        assertEquals(Optional.empty(), packet.playbackSessionId());
    }

    private record TestSyncPayload(UUID ownerId, UUID sourceId, int sourceType, int sourceEntityId,
            double sourceX, double sourceY, double sourceZ, boolean playing, int queueIndex, String playUrl,
            String rawUrl, String songName, int durationSeconds, int volumePerMille, String sessionId,
            long elapsedMillis, boolean headphoneRouted) implements ClientMediaSyncPayload {
    }

    private record TestTimelinePayload(UUID sourceId, String sessionId, long elapsedMillis, int volumePerMille,
            boolean headphoneRouted) implements ClientMediaTimelinePayload {
    }
}
