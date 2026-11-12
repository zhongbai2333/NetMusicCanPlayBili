package com.zhongbai233.net_music_can_play_bili.client.sync;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientMediaPayloadIdentityTest {

    @Test
    void timelinePacketRejectsInvalidTypedSessionAtRuntimeBoundary() {
        ClientMediaTimelinePayload packet = new TestTimelinePayload(UUID.randomUUID(), "bad=session",
                1_000L, 700, false);

        assertEquals("bad=session", packet.sessionId());
        assertEquals(Optional.empty(), packet.playbackSessionId());
    }

    private record TestTimelinePayload(UUID sourceId, String sessionId, long elapsedMillis, int volumePerMille,
            boolean headphoneRouted) implements ClientMediaTimelinePayload {
    }
}
