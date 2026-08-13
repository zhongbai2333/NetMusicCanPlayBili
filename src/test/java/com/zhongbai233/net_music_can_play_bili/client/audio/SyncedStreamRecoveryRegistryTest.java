package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncedStreamRecoveryRegistryTest {
    @AfterEach
    void tearDown() {
        SyncedStreamRecoveryRegistry.clear();
    }

    @Test
    void replacementRegistrationSurvivesStaleExactUnregister() throws Exception {
        PlaybackSessionId sessionId = PlaybackSessionId.of("recovery-session");
        URL failedUrl = URI.create("https://example.com/audio.m4s").toURL();
        AtomicReference<SyncedStreamRecoveryRegistry.RecoveryRequest> observed = new AtomicReference<>();

        SyncedStreamRecoveryRegistry.Registration stale = SyncedStreamRecoveryRegistry.register(
                sessionId.value(), ignored -> false);
        SyncedStreamRecoveryRegistry.Registration replacement = SyncedStreamRecoveryRegistry.register(
                sessionId, request -> {
                    observed.set(request);
                    return true;
                });

        SyncedStreamRecoveryRegistry.unregister(stale);

        assertTrue(SyncedStreamRecoveryRegistry.reportFailure(sessionId, failedUrl, null));
        assertEquals(sessionId, observed.get().playbackSessionId());
        assertEquals(sessionId.value(), observed.get().sessionId());
        assertEquals(1, observed.get().attempt());

        SyncedStreamRecoveryRegistry.unregister(replacement);
        assertFalse(SyncedStreamRecoveryRegistry.reportFailure(sessionId, failedUrl, null));
    }

    @Test
    void malformedStringFacadeCannotCreateOrAddressEntry() throws Exception {
        URL failedUrl = URI.create("https://example.com/audio.m4s").toURL();
        SyncedStreamRecoveryRegistry.Registration registration = SyncedStreamRecoveryRegistry.register(
                "invalid session", ignored -> true);

        assertEquals("", registration.sessionId());
        assertTrue(registration.playbackSessionId().isEmpty());
        assertFalse(SyncedStreamRecoveryRegistry.reportFailure("invalid session", failedUrl, null));
    }
}
