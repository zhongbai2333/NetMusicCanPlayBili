package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMediaTimelineViewTest {
    @Test
    void typedPlaybackAndViewKeepStringFacades() {
        PlaybackSessionId sessionId = PlaybackSessionId.of("handheld-session");
        MediaTimelineClock.TimelineSnapshot timeline = new MediaTimelineClock.TimelineSnapshot(
                sessionId.value(), 1_000L, 1_000L, 1_000L, 1_000L, 60_000L, 0L);
        HandheldMediaPlayback playback = new HandheldMediaPlayback(Optional.of(sessionId), "raw", "title", timeline,
                false);

        ClientMediaTimelineView view = ClientMediaTimelineView.forHandheldOwner(
                UUID.randomUUID(), playback, false, 1_000L, 60_000L);

        assertEquals(Optional.of(sessionId), playback.playbackSessionId());
        assertEquals(sessionId.value(), playback.sessionId());
        assertTrue(playback.hasSession());
        assertEquals(Optional.of(sessionId), view.playbackSessionId());
        assertEquals(sessionId.value(), view.sessionId());
    }

    @Test
    void malformedSessionCannotBecomeHandheldIdentity() {
        HandheldMediaPlayback playback = new HandheldMediaPlayback("invalid session", "raw", "title",
                MediaTimelineClock.TimelineSnapshot.EMPTY, false);

        ClientMediaTimelineView view = ClientMediaTimelineView.forHandheldOwner(
                UUID.randomUUID(), playback, false, 500L, 10_000L);

        assertTrue(playback.playbackSessionId().isEmpty());
        assertFalse(playback.hasSession());
        assertTrue(view.playbackSessionId().isEmpty());
        assertEquals("", view.sessionId());
    }
}
