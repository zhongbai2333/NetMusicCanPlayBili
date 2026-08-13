package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClientMediaPlaybackSessionsTest {
    @BeforeEach
    @AfterEach
    void clearRegistries() {
        ClientMediaSoundRegistry.clearAndDiscard();
        ClientMediaPlaybackRegistry.clear();
    }

    @Test
    void stopInvalidatesSourceBeforeImmediatelyDiscardingItsSound() {
        UUID stoppedSource = UUID.randomUUID();
        UUID otherSource = UUID.randomUUID();
        TestSound stopped = registerCurrent(stoppedSource, PlaybackSessionId.of("stopped-session"));
        TestSound other = registerCurrent(otherSource, PlaybackSessionId.of("other-session"));
        AtomicInteger carrierStops = new AtomicInteger();

        ClientMediaPlaybackSessions.stop(stoppedSource, ignored -> carrierStops.incrementAndGet());
        ClientMediaPlaybackSessions.stop(stoppedSource, ignored -> carrierStops.incrementAndGet());

        assertFalse(ClientMediaPlaybackRegistry.contains(stoppedSource));
        assertNull(ClientMediaSoundRegistry.get(stoppedSource));
        assertEquals(1, stopped.discards());
        assertSame(other, ClientMediaSoundRegistry.get(otherSource));
        assertEquals(0, other.discards());
        assertEquals(2, carrierStops.get());
    }

    @Test
    void clearAllDrainsEverySoundExactlyOnce() {
        UUID firstSource = UUID.randomUUID();
        UUID secondSource = UUID.randomUUID();
        TestSound first = registerCurrent(firstSource, PlaybackSessionId.of("first-session"));
        TestSound second = registerCurrent(secondSource, PlaybackSessionId.of("second-session"));
        AtomicInteger carrierClears = new AtomicInteger();

        ClientMediaPlaybackSessions.clearAll(carrierClears::incrementAndGet);
        ClientMediaPlaybackSessions.clearAll(carrierClears::incrementAndGet);

        assertFalse(ClientMediaPlaybackRegistry.contains(firstSource));
        assertFalse(ClientMediaPlaybackRegistry.contains(secondSource));
        assertNull(ClientMediaSoundRegistry.get(firstSource));
        assertNull(ClientMediaSoundRegistry.get(secondSource));
        assertEquals(1, first.discards());
        assertEquals(1, second.discards());
        assertEquals(2, carrierClears.get());
    }

    private static TestSound registerCurrent(UUID sourceId, PlaybackSessionId sessionId) {
        ClientMediaSyncPayload payload = new TestSyncPayload(sourceId, sourceId, ClientMediaSyncPayload.SOURCE_BLOCK,
                0, 0.0D, 0.0D, 0.0D, true, 0, "https://example.invalid/play",
                "https://example.invalid/raw", "", 1, 1_000, sessionId.value(), 0L, false);
        ClientMediaPlaybackRegistry.put(sourceId, ClientMediaPlaybackRegistry.createFromSync(payload));
        TestSound sound = new TestSound(sessionId.value());
        ClientMediaSoundRegistry.register(sourceId, sessionId, sound);
        assertSame(sound, ClientMediaSoundRegistry.get(sourceId));
        return sound;
    }

    private record TestSyncPayload(UUID ownerId, UUID sourceId, int sourceType, int sourceEntityId,
            double sourceX, double sourceY, double sourceZ, boolean playing, int queueIndex, String playUrl,
            String rawUrl, String songName, int durationSeconds, int volumePerMille, String sessionId,
            long elapsedMillis, boolean headphoneRouted) implements ClientMediaSyncPayload {
    }

    private static final class TestSound implements ClientMediaSoundHandle {
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
}
