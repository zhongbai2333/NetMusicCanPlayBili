package com.zhongbai233.net_music_can_play_bili.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientPlaybackCommandTest {
    @Test
    void normalizesProtocolSnapshot() {
        ClientPlaybackCommand command = new ClientPlaybackCommand(1, 2, 3, null, null, null, 0, null,
                -100L, -200L, null, false, false);

        assertEquals("", command.rawUrl());
        assertEquals("", command.playUrl());
        assertEquals("", command.songName());
        assertEquals(1, command.remainingSeconds());
        assertEquals(0L, command.elapsedMillis());
        assertEquals(0L, command.totalMillis());
        assertFalse(command.hasSession());
    }

    @Test
    void clampsElapsedToKnownDurationAndPreservesSession() {
        ClientPlaybackCommand command = new ClientPlaybackCommand(1, 2, 3, "raw", "play", "song", 12,
                "source-100-3", 15_000L, 10_000L, null, true, true);

        assertEquals(10_000L, command.elapsedMillis());
        assertEquals(10_000L, command.totalMillis());
        assertEquals(10_000L, command.durationMillis());
        assertTrue(command.hasSession());
        assertEquals("source-100-3", command.syncMetadata().sessionId());
    }
}