package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaTimelineClockTest {
    @Test
    void absorbsSameGameTimeObservationOnlyOnce() {
        assertTrue(MediaTimelineClock.isNewObservation(42L, 1_100L, 60_000L,
            Long.MIN_VALUE, 0L, 0L));
        assertFalse(MediaTimelineClock.isNewObservation(42L, 1_100L, 60_000L,
            42L, 1_100L, 60_000L));
        assertTrue(MediaTimelineClock.isNewObservation(43L, 1_100L, 60_000L,
            42L, 1_100L, 60_000L));
    }

    @Test
    void typedSessionKeepsStringFacade() {
        PlaybackSessionId sessionId = PlaybackSessionId.of("timeline-session");
        MediaTimelineClock clock = MediaTimelineClock.start(sessionId.value(), 1_000L, 60_000L);

        assertEquals(Optional.of(sessionId), clock.playbackSessionId());
        assertEquals(sessionId.value(), clock.sessionId());
        assertTrue(clock.isForSession(Optional.of(sessionId)));
        assertTrue(clock.isForSession(" timeline-session "));
        MediaTimelineClock.TimelineSnapshot snapshot = new MediaTimelineClock.TimelineSnapshot(
                Optional.of(sessionId), 1_000L, 1_000L, 1_000L, 1_000L, 60_000L, 0L);
        assertEquals(Optional.of(sessionId), snapshot.playbackSessionId());
        assertEquals(sessionId.value(), snapshot.sessionId());
    }

    @Test
    void invalidSessionNormalizesToEmptyIdentity() {
        MediaTimelineClock clock = MediaTimelineClock.start("invalid session", 1_000L, 60_000L);

        assertTrue(clock.playbackSessionId().isEmpty());
        assertEquals("", clock.sessionId());
        assertTrue(clock.isForSession((String) null));
        assertFalse(clock.isForSession("different-session"));
    }
}
