package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMediaRetryDispatchTest {
    @Test
    void acceptedDispatchKeepsPendingOwnership() {
        TestRetryPolicy policy = new TestRetryPolicy(true, false);
        AtomicInteger rejections = new AtomicInteger();

        assertTrue(ClientMediaRetryDispatch.dispatch(policy, UUID.randomUUID(),
                PlaybackSessionId.of("accepted-retry"), active("accepted-retry"), null,
                rejections::incrementAndGet));
        assertEquals(1, policy.dispatches());
        assertEquals(0, rejections.get());
    }

    @Test
    void rejectedDispatchRollsBackExactlyOnce() {
        TestRetryPolicy policy = new TestRetryPolicy(false, false);
        AtomicInteger rejections = new AtomicInteger();

        assertFalse(ClientMediaRetryDispatch.dispatch(policy, UUID.randomUUID(),
                PlaybackSessionId.of("rejected-retry"), active("rejected-retry"), null,
                rejections::incrementAndGet));
        assertEquals(1, policy.dispatches());
        assertEquals(1, rejections.get());
    }

    @Test
    void dispatchExceptionAlsoConvergesRejectedOwnership() {
        TestRetryPolicy policy = new TestRetryPolicy(false, true);
        AtomicInteger rejections = new AtomicInteger();

        assertFalse(ClientMediaRetryDispatch.dispatch(policy, UUID.randomUUID(),
                PlaybackSessionId.of("failed-retry"), active("failed-retry"), new IllegalStateException("stream"),
                rejections::incrementAndGet));
        assertEquals(1, policy.dispatches());
        assertEquals(1, rejections.get());
    }

    private static ClientMediaPlaybackRegistry.ActivePlayback active(String sessionId) {
        UUID sourceId = UUID.randomUUID();
        ClientMediaSyncPayload payload = new TestSyncPayload(sourceId, sourceId, ClientMediaSyncPayload.SOURCE_BLOCK,
                0, 0.0D, 0.0D, 0.0D, true, 0, "https://example.invalid/play",
                "https://example.invalid/raw", "song", 60, 1_000, sessionId, 1_000L, false);
        return ClientMediaPlaybackRegistry.createFromSync(payload);
    }

    private record TestSyncPayload(UUID ownerId, UUID sourceId, int sourceType, int sourceEntityId,
            double sourceX, double sourceY, double sourceZ, boolean playing, int queueIndex, String playUrl,
            String rawUrl, String songName, int durationSeconds, int volumePerMille, String sessionId,
            long elapsedMillis, boolean headphoneRouted) implements ClientMediaSyncPayload {
    }

    private static final class TestRetryPolicy implements ClientMediaRetryPolicy {
        private final boolean accepted;
        private final boolean fail;
        private final AtomicInteger dispatches = new AtomicInteger();

        private TestRetryPolicy(boolean accepted, boolean fail) {
            this.accepted = accepted;
            this.fail = fail;
        }

        @Override
        public long retryDelayMillis() {
            return 0L;
        }

        @Override
        public void scheduleRetry(UUID deviceId, String sessionId,
                ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
            tryScheduleRetry(deviceId, sessionId, active, error);
        }

        @Override
        public boolean tryScheduleRetry(UUID deviceId, String sessionId,
                ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
            dispatches.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("dispatch failed");
            }
            return accepted;
        }

        int dispatches() {
            return dispatches.get();
        }
    }
}
