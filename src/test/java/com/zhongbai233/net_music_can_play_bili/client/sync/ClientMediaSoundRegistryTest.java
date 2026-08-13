package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMediaSoundRegistryTest {
    @BeforeEach
    @AfterEach
    void clearRegistries() {
        ClientMediaSoundRegistry.clear();
        ClientMediaPlaybackRegistry.clear();
    }

    @Test
    void soundHandleExposesTypedIdentityForExactMatching() {
        PlaybackSessionId sessionId = PlaybackSessionId.of("sound-session");
        TestSound sound = new TestSound(sessionId.value());

        assertEquals(Optional.of(sessionId), sound.playbackSession());
        assertTrue(ClientMediaSoundRegistry.matchesSession(sound, sessionId));
        assertFalse(ClientMediaSoundRegistry.matchesSession(sound, PlaybackSessionId.of("stale-session")));
    }

    @Test
    void invalidSoundIdentityCannotMatchTypedSession() {
        PlaybackSessionId sessionId = PlaybackSessionId.of("current-session");
        TestSound invalid = new TestSound("invalid session");

        assertEquals(Optional.empty(), invalid.playbackSession());
        assertFalse(ClientMediaSoundRegistry.matchesSession(invalid, sessionId));
        assertFalse(ClientMediaSoundRegistry.matchesSession(null, sessionId));
    }

    @Test
    void acceptingReplacementSessionRetiresOldSound() {
        UUID sourceId = UUID.randomUUID();
        PlaybackSessionId oldSession = PlaybackSessionId.of("old-session");
        PlaybackSessionId replacementSession = PlaybackSessionId.of("replacement-session");
        TestSound oldSound = registerCurrent(sourceId, oldSession);

        putActive(sourceId, replacementSession);
        ClientMediaSoundRegistry.onSessionAccepted(sourceId, replacementSession);

        assertNull(ClientMediaSoundRegistry.get(sourceId));
        assertEquals(1, oldSound.discards());
    }

    @Test
    void acceptingSameSessionKeepsCurrentSound() {
        UUID sourceId = UUID.randomUUID();
        PlaybackSessionId sessionId = PlaybackSessionId.of("same-session");
        TestSound sound = registerCurrent(sourceId, sessionId);

        ClientMediaSoundRegistry.onSessionAccepted(sourceId, sessionId);

        assertSame(sound, ClientMediaSoundRegistry.get(sourceId));
        assertEquals(0, sound.discards());
    }

    @Test
    void lateOldSessionFinishCannotRemoveReplacementSound() {
        UUID sourceId = UUID.randomUUID();
        PlaybackSessionId oldSession = PlaybackSessionId.of("old-session");
        PlaybackSessionId replacementSession = PlaybackSessionId.of("replacement-session");
        TestSound replacement = registerCurrent(sourceId, replacementSession);

        ClientMediaSoundRegistry.finish(sourceId, oldSession);

        assertSame(replacement, ClientMediaSoundRegistry.get(sourceId));
        assertEquals(0, replacement.discards());
    }

    @Test
    void finishingCurrentSessionImmediatelyDiscardsItsSound() {
        UUID sourceId = UUID.randomUUID();
        PlaybackSessionId sessionId = PlaybackSessionId.of("finished-session");
        TestSound sound = registerCurrent(sourceId, sessionId);

        ClientMediaPlaybackRegistry.finishSession(sourceId, sessionId);
        ClientMediaPlaybackRegistry.finishSession(sourceId, sessionId);

        assertFalse(ClientMediaPlaybackRegistry.contains(sourceId));
        assertNull(ClientMediaSoundRegistry.get(sourceId));
        assertEquals(1, sound.discards());
    }

    @Test
    void acceptingReplacementDoesNotAffectOtherSource() {
        UUID replacedSource = UUID.randomUUID();
        UUID otherSource = UUID.randomUUID();
        PlaybackSessionId oldSession = PlaybackSessionId.of("old-session");
        PlaybackSessionId replacementSession = PlaybackSessionId.of("replacement-session");
        PlaybackSessionId otherSession = PlaybackSessionId.of("other-session");
        TestSound replaced = registerCurrent(replacedSource, oldSession);
        TestSound other = registerCurrent(otherSource, otherSession);

        putActive(replacedSource, replacementSession);
        ClientMediaSoundRegistry.onSessionAccepted(replacedSource, replacementSession);

        assertNull(ClientMediaSoundRegistry.get(replacedSource));
        assertEquals(1, replaced.discards());
        assertSame(other, ClientMediaSoundRegistry.get(otherSource));
        assertEquals(0, other.discards());
    }

    @Test
    void staleFactoryRegistrationIsRejectedAndDiscarded() {
        UUID sourceId = UUID.randomUUID();
        PlaybackSessionId activeSession = PlaybackSessionId.of("active-session");
        PlaybackSessionId staleSession = PlaybackSessionId.of("stale-session");
        putActive(sourceId, activeSession);
        TestSound stale = new TestSound(staleSession.value());

        assertFalse(ClientMediaSoundRegistry.tryRegister(sourceId, staleSession, stale));

        assertNull(ClientMediaSoundRegistry.get(sourceId));
        assertTrue(stale.stopped());
        assertEquals(1, stale.discards());
    }

    @Test
    void racingStaleFactoryCannotOverwriteReplacementSound() throws Exception {
        UUID sourceId = UUID.randomUUID();
        PlaybackSessionId staleSession = PlaybackSessionId.of("stale-session");
        PlaybackSessionId replacementSession = PlaybackSessionId.of("replacement-session");
        putActive(sourceId, staleSession);
        BlockingIdentitySound stale = new BlockingIdentitySound(staleSession.value());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> staleRegistration = executor.submit(
                    () -> ClientMediaSoundRegistry.tryRegister(sourceId, staleSession, stale));
            assertTrue(stale.awaitIdentityRead());

            putActive(sourceId, replacementSession);
            TestSound replacement = new TestSound(replacementSession.value());
            assertTrue(ClientMediaSoundRegistry.tryRegister(sourceId, replacementSession, replacement));
            stale.releaseIdentity();

            assertFalse(staleRegistration.get(5L, TimeUnit.SECONDS));
            assertSame(replacement, ClientMediaSoundRegistry.get(sourceId));
            assertEquals(1, stale.discards());
            assertEquals(0, replacement.discards());
        } finally {
            stale.releaseIdentity();
            executor.shutdownNow();
        }
    }

    @Test
    void acceptedDuplicateFactoryDiscardsPreviousHandle() {
        UUID sourceId = UUID.randomUUID();
        PlaybackSessionId sessionId = PlaybackSessionId.of("duplicate-session");
        TestSound previous = registerCurrent(sourceId, sessionId);
        TestSound replacement = new TestSound(sessionId.value());

        assertTrue(ClientMediaSoundRegistry.tryRegister(sourceId, sessionId, replacement));

        assertSame(replacement, ClientMediaSoundRegistry.get(sourceId));
        assertEquals(1, previous.discards());
        assertEquals(0, replacement.discards());
    }

    private static TestSound registerCurrent(UUID sourceId, PlaybackSessionId sessionId) {
        putActive(sourceId, sessionId);
        TestSound sound = new TestSound(sessionId.value());
        ClientMediaSoundRegistry.register(sourceId, sessionId, sound);
        assertSame(sound, ClientMediaSoundRegistry.get(sourceId));
        return sound;
    }

    private static void putActive(UUID sourceId, PlaybackSessionId sessionId) {
        ClientMediaSyncPayload payload = new TestSyncPayload(sourceId, sourceId, ClientMediaSyncPayload.SOURCE_BLOCK,
                0, 0.0D, 0.0D, 0.0D, true, 0, "https://example.invalid/play",
                "https://example.invalid/raw", "", 1, 1_000, sessionId.value(), 0L, false);
        ClientMediaPlaybackRegistry.put(sourceId, ClientMediaPlaybackRegistry.createFromSync(payload));
    }

    private record TestSyncPayload(UUID ownerId, UUID sourceId, int sourceType, int sourceEntityId,
            double sourceX, double sourceY, double sourceZ, boolean playing, int queueIndex, String playUrl,
            String rawUrl, String songName, int durationSeconds, int volumePerMille, String sessionId,
            long elapsedMillis, boolean headphoneRouted) implements ClientMediaSyncPayload {
    }

    private static class TestSound implements ClientMediaSoundHandle {
        private final String sessionId;
        private final AtomicBoolean discarded = new AtomicBoolean();
        private final AtomicInteger discards = new AtomicInteger();

        private TestSound(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public boolean headphoneRouted() {
            return false;
        }

        @Override
        public boolean stopped() {
            return discarded.get();
        }

        @Override
        public void discardWithoutFinishing() {
            if (discarded.compareAndSet(false, true)) {
                discards.incrementAndGet();
            }
        }

        @Override
        public void setMediaVolume(float volume) {
        }

        int discards() {
            return discards.get();
        }
    }

    private static final class BlockingIdentitySound extends TestSound {
        private final AtomicBoolean blocked = new AtomicBoolean();
        private final CountDownLatch identityRead = new CountDownLatch(1);
        private final CountDownLatch continueIdentity = new CountDownLatch(1);

        private BlockingIdentitySound(String sessionId) {
            super(sessionId);
        }

        @Override
        public Optional<PlaybackSessionId> playbackSession() {
            if (blocked.compareAndSet(false, true)) {
                identityRead.countDown();
                try {
                    continueIdentity.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
            return PlaybackSessionId.parse(sessionId());
        }

        boolean awaitIdentityRead() throws InterruptedException {
            return identityRead.await(5L, TimeUnit.SECONDS);
        }

        void releaseIdentity() {
            continueIdentity.countDown();
        }
    }
}
