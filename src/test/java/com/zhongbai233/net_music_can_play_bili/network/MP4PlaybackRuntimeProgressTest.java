package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MP4PlaybackRuntimeProgressTest {
    @Test
    void keepsTypedSessionAndStringFacade() {
        PlaybackSessionId sessionId = PlaybackSessionId.of("mp4-runtime-session");

        MP4PlaybackRuntimeProgress progress = new MP4PlaybackRuntimeProgress(
                2, 1_500L, 10, 700, Optional.of(sessionId), true);

        assertEquals(Optional.of(sessionId), progress.playbackSessionId());
        assertEquals(sessionId.value(), progress.sessionId());
    }

    @Test
    void stringFacadeRejectsMalformedSession() {
        MP4PlaybackRuntimeProgress progress = new MP4PlaybackRuntimeProgress(
                0, 0L, 0, 700, "invalid session", false);

        assertTrue(progress.playbackSessionId().isEmpty());
        assertEquals("", progress.sessionId());
    }

    @Test
    void clampsRuntimeValuesBeforePersistence() {
        MP4PlaybackRuntimeProgress progress = new MP4PlaybackRuntimeProgress(
                -1, 20_000L, 10, 2_000, Optional.empty(), true);

        assertEquals(0, progress.queueIndex());
        assertEquals(9_950L, progress.elapsedMillis());
        assertEquals(10, progress.durationSeconds());
        assertEquals(1_000, progress.volumePerMille());
    }
}
