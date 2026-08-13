package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadPlaybackSessionIdsTest {
    @Test
    void generatedSessionRoundTripsItsPointIdentity() {
        UUID deviceId = UUID.randomUUID();
        UUID pointId = UUID.randomUUID();

        PlaybackSessionId sessionId = PadPlaybackSessionIds.create(deviceId, pointId, 42L);

        assertEquals(pointId, PadPlaybackSessionIds.pointId(sessionId.value()));
        assertTrue(PadPlaybackSessionIds.matches(sessionId.value(), deviceId, pointId));
        assertTrue(PadPlaybackSessionIds.isPadSession(sessionId.value()));
    }

    @Test
    void matchingRejectsAnotherDeviceOrPoint() {
        UUID deviceId = UUID.randomUUID();
        UUID pointId = UUID.randomUUID();
        String sessionId = PadPlaybackSessionIds.create(deviceId, pointId, 1L).value();

        assertFalse(PadPlaybackSessionIds.matches(sessionId, UUID.randomUUID(), pointId));
        assertFalse(PadPlaybackSessionIds.matches(sessionId, deviceId, UUID.randomUUID()));
    }

    @Test
    void malformedOrNonPadIdentityDoesNotExposeAPoint() {
        assertNull(PadPlaybackSessionIds.pointId("not-a-pad-session"));
        assertNull(PadPlaybackSessionIds.pointId("device-pad-not-a-uuid"));
        UUID deviceId = UUID.randomUUID();
        UUID pointId = UUID.randomUUID();
        assertNull(PadPlaybackSessionIds.pointId(deviceId + "-pad-" + pointId));
        assertNull(PadPlaybackSessionIds.pointId(deviceId + "-pad-" + pointId + "-generation"));
        assertNull(PadPlaybackSessionIds.pointId("not-a-uuid-pad-" + pointId + "-1"));
        assertFalse(PadPlaybackSessionIds.isPadSession(null));
    }

    @Test
    void creationRequiresBothStableIdentities() {
        assertThrows(IllegalArgumentException.class,
                () -> PadPlaybackSessionIds.create(null, UUID.randomUUID(), 0L));
        assertThrows(IllegalArgumentException.class,
                () -> PadPlaybackSessionIds.create(UUID.randomUUID(), null, 0L));
    }
}
