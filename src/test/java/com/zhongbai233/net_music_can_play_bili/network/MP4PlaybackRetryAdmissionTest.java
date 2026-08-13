package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MP4PlaybackRetryAdmissionTest {
    @Test
    void matchingRetryCanRefreshTransportAndRetainLogicalSession() {
        Fixture fixture = new Fixture();
        UUID deviceId = UUID.randomUUID();
        PlaybackSessionId sessionId = PlaybackSessionId.of("logical-session");
        fixture.sessions.replace(deviceId, new Session(sessionId, 2, "BV1", "old-direct", 650));

        MP4PlaybackRetryAdmission.Attempt attempt = fixture.admission.begin(deviceId, sessionId);
        Session refreshed = fixture.admission.replaceIfCurrent(deviceId, attempt,
                current -> current.withDirectUrl("new-direct"));

        assertEquals(sessionId, refreshed.sessionId());
        assertEquals("new-direct", refreshed.directUrl());
        assertSame(refreshed, fixture.sessions.get(deviceId));
        assertFalse(fixture.admission.isCurrent(deviceId, attempt));
    }

    @Test
    void mismatchedExpectedSessionIsRejectedBeforeResolveStarts() {
        Fixture fixture = new Fixture();
        UUID deviceId = UUID.randomUUID();
        fixture.sessions.replace(deviceId, new Session(PlaybackSessionId.of("current"), 0, "BV1", "direct", 500));

        assertNull(fixture.admission.begin(deviceId, PlaybackSessionId.of("stale")));
    }

    @Test
    void userSeekGenerationRejectsLateRetryCompletion() {
        Fixture fixture = new Fixture();
        UUID deviceId = UUID.randomUUID();
        PlaybackSessionId sessionId = PlaybackSessionId.of("before-seek");
        Session current = new Session(sessionId, 0, "BV1", "direct", 500);
        fixture.sessions.replace(deviceId, current);
        MP4PlaybackRetryAdmission.Attempt retry = fixture.admission.begin(deviceId, sessionId);

        fixture.resolveIntents.replace(deviceId, 0, "BV1");

        assertFalse(fixture.admission.isCurrent(deviceId, retry));
        assertNull(fixture.admission.replaceIfCurrent(deviceId, retry,
                session -> session.withDirectUrl("late-retry")));
        assertSame(current, fixture.sessions.get(deviceId));
    }

    @Test
    void retryCannotSupersedeAUserSeekAlreadyResolving() {
        Fixture fixture = new Fixture();
        UUID deviceId = UUID.randomUUID();
        PlaybackSessionId sessionId = PlaybackSessionId.of("before-pending-seek");
        fixture.sessions.replace(deviceId, new Session(sessionId, 0, "BV1", "direct", 500));
        MP4ResolveIntentRegistry.Intent seek = fixture.resolveIntents.replace(deviceId, 1, "BV2");

        assertNull(fixture.admission.begin(deviceId, sessionId));
        assertTrue(fixture.resolveIntents.isCurrent(deviceId, seek));
    }

    @Test
    void replacementSessionCannotBeOverwrittenByLateRetry() {
        Fixture fixture = new Fixture();
        UUID deviceId = UUID.randomUUID();
        PlaybackSessionId oldSessionId = PlaybackSessionId.of("old-session");
        fixture.sessions.replace(deviceId, new Session(oldSessionId, 0, "BV1", "direct", 500));
        MP4PlaybackRetryAdmission.Attempt retry = fixture.admission.begin(deviceId, oldSessionId);
        Session replacement = new Session(PlaybackSessionId.of("replacement"), 0, "BV1", "new", 500);

        fixture.sessions.replace(deviceId, replacement);

        assertNull(fixture.admission.replaceIfCurrent(deviceId, retry,
                session -> session.withDirectUrl("late-retry")));
        assertSame(replacement, fixture.sessions.get(deviceId));
    }

    @Test
    void queueMutationWithSameSessionAlsoInvalidatesRetryOwnership() {
        Fixture fixture = new Fixture();
        UUID deviceId = UUID.randomUUID();
        PlaybackSessionId sessionId = PlaybackSessionId.of("same-session");
        fixture.sessions.replace(deviceId, new Session(sessionId, 0, "BV1", "direct", 500));
        MP4PlaybackRetryAdmission.Attempt retry = fixture.admission.begin(deviceId, sessionId);
        Session moved = new Session(sessionId, 1, "BV2", "other", 500);

        fixture.sessions.replace(deviceId, moved);

        assertFalse(fixture.admission.isCurrent(deviceId, retry));
        assertSame(moved, fixture.sessions.get(deviceId));
    }

    private static final class Fixture {
        private final MP4PlaybackSourceSessionRegistry<Session> sessions = new MP4PlaybackSourceSessionRegistry<>();
        private final MP4ResolveIntentRegistry resolveIntents = new MP4ResolveIntentRegistry();
        private final MP4PlaybackRetryAdmission<Session> admission = new MP4PlaybackRetryAdmission<>(sessions,
                resolveIntents, session -> session.sessionId(), session -> session.queueIndex(),
                session -> session.sourceUrl());
    }

    private record Session(PlaybackSessionId sessionId, int queueIndex, String sourceUrl, String directUrl,
            int volumePerMille) {
        Session withDirectUrl(String value) {
            return new Session(sessionId, queueIndex, sourceUrl, value, volumePerMille);
        }
    }
}
