package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackSessionIdTest {
    @Test
    void acceptsGeneratedSessionIdentityCharacters() {
        PlaybackSessionId sessionId = PlaybackSessionId.of("minecart-123e4567-e89b-12d3-a456-426614174000:-7");

        assertEquals("minecart-123e4567-e89b-12d3-a456-426614174000:-7", sessionId.value());
        assertEquals(sessionId.value(), sessionId.toString());
    }

    @Test
    void parseTrimsTransportBoundaryWhitespace() {
        assertEquals(PlaybackSessionId.of("session-1"),
                PlaybackSessionId.parse(" session-1 ").orElseThrow());
    }

    @Test
    void parseRejectsMissingAndFragmentDelimiterValues() {
        assertTrue(PlaybackSessionId.parse(null).isEmpty());
        assertTrue(PlaybackSessionId.parse(" ").isEmpty());
        assertTrue(PlaybackSessionId.parse("session&other=1").isEmpty());
        assertTrue(PlaybackSessionId.parse("session#other").isEmpty());
        assertTrue(PlaybackSessionId.parse("session=other").isEmpty());
    }

    @Test
    void rejectsValuesLongerThanThePacketBoundary() {
        assertThrows(IllegalArgumentException.class, () -> PlaybackSessionId.of("s".repeat(129)));
    }
}
