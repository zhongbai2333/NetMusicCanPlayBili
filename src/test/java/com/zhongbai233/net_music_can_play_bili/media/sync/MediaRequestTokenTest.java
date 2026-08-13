package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaRequestTokenTest {
    @Test
    void normalizesSafeSerializedValues() {
        MediaRequestToken token = new MediaRequestToken("  request-123_test.value~  ");

        assertEquals("request-123_test.value~", token.value());
        assertEquals(token.value(), token.toString());
    }

    @Test
    void rejectsBlankReservedAndOversizedValues() {
        assertThrows(IllegalArgumentException.class, () -> new MediaRequestToken(" "));
        assertThrows(IllegalArgumentException.class, () -> new MediaRequestToken("request&other"));
        assertThrows(IllegalArgumentException.class, () -> new MediaRequestToken("request#fragment"));
        assertThrows(IllegalArgumentException.class, () -> new MediaRequestToken("x".repeat(129)));
    }

    @Test
    void parserTurnsUntrustedValuesIntoOptionalTokens() {
        assertEquals("request-1", MediaRequestToken.parse(" request-1 ").orElseThrow().value());
        assertTrue(MediaRequestToken.parse("request=1").isEmpty());
        assertTrue(MediaRequestToken.parse("").isEmpty());
        assertTrue(MediaRequestToken.parse(null).isEmpty());
    }

    @Test
    void randomTokensAreUniqueAndUrlSafe() {
        MediaRequestToken first = MediaRequestToken.random();
        MediaRequestToken second = MediaRequestToken.random();

        assertNotEquals(first, second);
        assertFalse(first.value().isBlank());
        assertTrue(MediaRequestToken.parse(first.value()).isPresent());
    }
}
