package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAudioOutputRegistryTest {
    @Test
    void audioTimelineKeepsTypedSessionAndStringFacades() {
        PlaybackSessionId sessionId = PlaybackSessionId.of("audio-session");
        ClientAudioOutputRegistry.AudioTimeline timeline = new ClientAudioOutputRegistry.AudioTimeline(
                10L, 20L, -1L, 10L, 0, 0, Optional.of(sessionId));

        assertEquals(Optional.of(sessionId), timeline.playbackSessionId());
        assertEquals("audio-session", timeline.audioSessionId());
        assertEquals("audio-session", timeline.sessionId());
    }

    @Test
    void legacyInvalidSessionIsNormalizedToAbsent() {
        ClientAudioOutputRegistry.AudioTimeline timeline = new ClientAudioOutputRegistry.AudioTimeline(
                -1L, -1L, -1L, -1L, 0, 0, "not a valid session");

        assertTrue(timeline.playbackSessionId().isEmpty());
        assertEquals("", timeline.audioSessionId());
    }
}
