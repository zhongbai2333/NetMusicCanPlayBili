package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackSourceIdTest {
    private static final UUID SOURCE_UUID = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void wrapsTheProtocolUuidWithoutChangingItsRepresentation() {
        PlaybackSourceId sourceId = PlaybackSourceId.of(SOURCE_UUID);

        assertEquals(SOURCE_UUID, sourceId.value());
        assertEquals(SOURCE_UUID.toString(), sourceId.toString());
    }

    @Test
    void parseAcceptsAUuidTransportValue() {
        assertEquals(PlaybackSourceId.of(SOURCE_UUID),
                PlaybackSourceId.parse(" " + SOURCE_UUID + " ").orElseThrow());
    }

    @Test
    void parseRejectsMissingOrMalformedValues() {
        assertTrue(PlaybackSourceId.parse(null).isEmpty());
        assertTrue(PlaybackSourceId.parse(" ").isEmpty());
        assertTrue(PlaybackSourceId.parse("not-a-uuid").isEmpty());
    }

    @Test
    void directConstructionRejectsNull() {
        assertThrows(NullPointerException.class, () -> PlaybackSourceId.of(null));
    }
}
