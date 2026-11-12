package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackIdentityBoundaryTest {
    @Test
    void acceptsAndNormalizesProtocolIdentities() {
        UUID source = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        assertEquals(source, PlaybackSourceId.parse(" " + source + " ").orElseThrow().value());
        assertEquals("session-1", PlaybackSessionId.parse(" session-1 ").orElseThrow().value());
        assertEquals("minecart-123e4567-e89b-12d3-a456-426614174000:-7",
                PlaybackSessionId.of("minecart-123e4567-e89b-12d3-a456-426614174000:-7").value());
        assertEquals("request-123_test.value~", new MediaRequestToken("  request-123_test.value~  ").value());
        assertEquals("request-1", MediaRequestToken.parse(" request-1 ").orElseThrow().value());
        assertEquals(source.toString(), PlaybackSourceId.of(source).toString());
        assertEquals("session-1", PlaybackSessionId.of(" session-1 ").toString());
        assertEquals("request-1", new MediaRequestToken(" request-1 ").toString());
    }

    @Test
    void missingMalformedAndFragmentDelimiterValuesCannotCrossProtocolBoundaries() {
        assertTrue(PlaybackSourceId.parse(null).isEmpty());
        assertTrue(PlaybackSourceId.parse(" ").isEmpty());
        assertTrue(PlaybackSourceId.parse("not-a-uuid").isEmpty());
        assertTrue(PlaybackSessionId.parse(null).isEmpty());
        assertTrue(PlaybackSessionId.parse(" ").isEmpty());
        assertTrue(PlaybackSessionId.parse("session&other=1").isEmpty());
        assertTrue(PlaybackSessionId.parse("session#other").isEmpty());
        assertTrue(PlaybackSessionId.parse("session=other").isEmpty());
        assertTrue(MediaRequestToken.parse("request=1").isEmpty());
        assertTrue(MediaRequestToken.parse("").isEmpty());
        assertTrue(MediaRequestToken.parse(null).isEmpty());
    }

    @Test
    void directConstructionRejectsNullReservedAndOversizedIdentities() {
        assertThrows(NullPointerException.class, () -> PlaybackSourceId.of(null));
        assertThrows(IllegalArgumentException.class, () -> PlaybackSessionId.of("s".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> new MediaRequestToken(" "));
        assertThrows(IllegalArgumentException.class, () -> new MediaRequestToken("request&other"));
        assertThrows(IllegalArgumentException.class, () -> new MediaRequestToken("request#fragment"));
        assertThrows(IllegalArgumentException.class, () -> new MediaRequestToken("x".repeat(129)));
    }

    @Test
    void requestTokensAreUniqueAndUrlSafe() {
        MediaRequestToken first = MediaRequestToken.random();
        MediaRequestToken second = MediaRequestToken.random();
        assertNotEquals(first, second);
        assertFalse(first.value().isBlank());
        assertTrue(MediaRequestToken.parse(first.value()).isPresent());
    }
}
