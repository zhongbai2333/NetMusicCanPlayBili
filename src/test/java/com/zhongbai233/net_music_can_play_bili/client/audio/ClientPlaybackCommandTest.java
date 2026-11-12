package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlaybackCommandTest {
    @Test
    void normalizesProtocolSnapshot() {
        ClientPlaybackCommand command = new ClientPlaybackCommand(1, 2, 3, null, null, null, 0, (String) null,
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
        assertEquals(PlaybackSessionId.of("source-100-3"), command.playbackSessionId().orElseThrow());
        assertEquals("source-100-3", command.syncMetadata().sessionId());
    }

    @Test
    void rejectsFragmentDelimiterSessionIdentityAtTheProtocolBoundary() {
        ClientPlaybackCommand command = new ClientPlaybackCommand(1, 2, 3, "raw", "play", "song", 12,
                "source-100&other=1", 1_000L, 10_000L, null, true, true);

        assertFalse(command.hasSession());
        assertTrue(command.playbackSessionId().isEmpty());
        assertEquals("", command.sessionId());
    }

    @Test
    void acceptsTheAuthoritativeSessionEvenWhenTrackerStillHasThePreviousSession() {
        assertEquals(ModernTurntableCommandAdmissionPolicy.Decision.ACCEPT_AUTHORITATIVE,
                ModernTurntableCommandAdmissionPolicy.decide("session-2", "session-2", "session-1"));
    }

    @Test
    void dropsACommandThatConflictsWithTheObservableAuthoritativeSession() {
        assertEquals(ModernTurntableCommandAdmissionPolicy.Decision.DROP_AUTHORITATIVE_SESSION_MISMATCH,
                ModernTurntableCommandAdmissionPolicy.decide("session-1", "session-2", "session-1"));
    }

    @Test
    void preservesCompatibilityFallbackWhenNoAuthoritativeOrTrackedSessionIsObservable() {
        assertEquals(ModernTurntableCommandAdmissionPolicy.Decision.ACCEPT_COMPATIBILITY_FALLBACK,
                ModernTurntableCommandAdmissionPolicy.decide("session-1", "", ""));
        assertEquals(ModernTurntableCommandAdmissionPolicy.Decision.ACCEPT_COMPATIBILITY_FALLBACK,
                ModernTurntableCommandAdmissionPolicy.decide("session-1", null, null));
    }

    @Test
    void rejectsDelayedPlaybackWhenTheAuthoritativeTurntableIsPresentButStopped() {
        assertEquals(ModernTurntableCommandAdmissionPolicy.Decision.DROP_AUTHORITATIVE_STOPPED,
                ModernTurntableCommandAdmissionPolicy.decide("session-1", "", "", true));
        assertEquals(ModernTurntableCommandAdmissionPolicy.Decision.DROP_AUTHORITATIVE_STOPPED,
                ModernTurntableCommandAdmissionPolicy.decide("session-1", "", "session-1", true));
    }

    @Test
    void explicitStopDominatesStaleAuthoritativeAndTrackedState() {
        assertEquals(ModernTurntableCommandAdmissionPolicy.Decision.DROP_EXPLICITLY_STOPPED,
                ModernTurntableCommandAdmissionPolicy.decide(
                        "session-1", "session-1", "session-1", true, true));
    }


    @Test
    void allowsTheTrackedSessionWhileAuthoritativeStateIsTemporarilyUnavailable() {
        assertEquals(ModernTurntableCommandAdmissionPolicy.Decision.ACCEPT_TRACKED,
                ModernTurntableCommandAdmissionPolicy.decide("session-2", "", "session-2"));
    }

    @Test
    void preventsAnOlderCommandFromReplacingTheTrackedSessionDuringFallback() {
        assertEquals(ModernTurntableCommandAdmissionPolicy.Decision.DROP_TRACKED_SESSION_MISMATCH,
                ModernTurntableCommandAdmissionPolicy.decide("session-1", "", "session-2"));
    }

    @Test
    void stopsOnlyTheExactActiveSession() {
        assertEquals(ModernTurntableStopPolicy.Decision.STOP_EXACT,
                ModernTurntableStopPolicy.decide("disc-session", "disc-session"));
    }

    @Test
    void staleOrMalformedStopsCannotKillTheReplacementSession() {
        assertEquals(ModernTurntableStopPolicy.Decision.IGNORE_STALE,
                ModernTurntableStopPolicy.decide("old-disc", "replacement-disc"));
        assertEquals(ModernTurntableStopPolicy.Decision.IGNORE_INVALID,
                ModernTurntableStopPolicy.decide("", "replacement-disc"));
        assertEquals(ModernTurntableStopPolicy.Decision.IGNORE_INVALID,
                ModernTurntableStopPolicy.decide("old-disc", null));
    }
}
