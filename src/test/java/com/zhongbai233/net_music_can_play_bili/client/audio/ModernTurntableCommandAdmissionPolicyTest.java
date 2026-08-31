package com.zhongbai233.net_music_can_play_bili.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModernTurntableCommandAdmissionPolicyTest {
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
    void allowsTheTrackedSessionWhileAuthoritativeStateIsTemporarilyUnavailable() {
        assertEquals(ModernTurntableCommandAdmissionPolicy.Decision.ACCEPT_TRACKED,
                ModernTurntableCommandAdmissionPolicy.decide("session-2", "", "session-2"));
    }

    @Test
    void preventsAnOlderCommandFromReplacingTheTrackedSessionDuringFallback() {
        assertEquals(ModernTurntableCommandAdmissionPolicy.Decision.DROP_TRACKED_SESSION_MISMATCH,
                ModernTurntableCommandAdmissionPolicy.decide("session-1", "", "session-2"));
    }
}
