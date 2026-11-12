package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientMediaTimelineBehaviorTest {
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

    @Test void lateLastFrameGetsAnActualDisplayInterval() {
        var clock = new VideoTailPresentation();
        long shown = clock.observe(1, 1_000_000_000L, false);
        assertFalse(VideoOnlyEndPolicy.ready(true,false,false,true,true,true,5000,1000,34,shown));
        shown = clock.observe(1, 1_034_000_000L, false);
        assertTrue(VideoOnlyEndPolicy.ready(true,false,false,true,true,true,5000,1000,34,shown));
        assertEquals(0,clock.observe(2,1_100_000_000L,false));
    }
    @Test void pausedTimeDoesNotPayTailDisplayBudget() {
        var clock = new VideoTailPresentation(); clock.observe(1,0,false);
        assertEquals(10,clock.observe(1,10_000_000L,false));
        assertEquals(10,clock.observe(1,20_000_000L,true));
        assertEquals(10,clock.observe(1,2_000_000_000L,true));
        assertEquals(10,clock.observe(1,3_000_000_000L,false));
        assertEquals(34,clock.observe(1,3_024_000_000L,false));
    }

    @Test void unknownDurationRemainsPlayableWhenVideoSourceAndSessionExist() {
        var timeline=new MediaTimelineClock.TimelineSnapshot("unknown",0,0,0,0,0,0);
        assertTrue(new HandheldMediaPlayback("unknown","https://example.test/video.mp4","title",timeline,false).hasPlayableVideoSource());
        assertFalse(new HandheldMediaPlayback("","https://example.test/video.mp4","title",timeline,false).hasPlayableVideoSource());
    }
    @Test void endWaitsForLastFrameIntervalAndRejectsPauseCancelOrStaleTransport() {
        assertFalse(VideoOnlyEndPolicy.ready(true,false,false,true,true,true,1010,1000,34,34));
        assertTrue(VideoOnlyEndPolicy.ready(true,false,false,true,true,true,1034,1000,34,34));
        assertFalse(VideoOnlyEndPolicy.ready(true,true,false,true,true,true,2000,1000,34,34));
        assertFalse(VideoOnlyEndPolicy.ready(true,false,true,true,true,true,2000,1000,34,34));
        assertFalse(VideoOnlyEndPolicy.ready(true,false,false,false,true,true,2000,1000,34,34));
        assertFalse(VideoOnlyEndPolicy.ready(true,false,false,true,true,false,2000,1000,34,34));
        assertFalse(VideoOnlyEndPolicy.ready(false,false,false,true,true,true,2000,1000,34,34));
    }
}
