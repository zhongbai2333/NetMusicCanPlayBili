package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMediaRetryRegistryTest {
    @Test
    void oneShotAdmissionRejectsDuplicateSourceSession() {
        ClientMediaRetryRegistry registry = new ClientMediaRetryRegistry();
        PlaybackSourceId sourceId = PlaybackSourceId.of(UUID.randomUUID());
        PlaybackSessionId sessionId = PlaybackSessionId.of("retry-session");

        assertTrue(registry.tryMark(sourceId, sessionId));
        assertFalse(registry.tryMark(sourceId, sessionId));
        assertTrue(registry.contains(sourceId, sessionId));
        assertEquals(1, registry.size());
    }

    @Test
    void concurrentAdmissionHasExactlyOneWinner() throws Exception {
        ClientMediaRetryRegistry registry = new ClientMediaRetryRegistry();
        PlaybackSourceId sourceId = PlaybackSourceId.of(UUID.randomUUID());
        PlaybackSessionId sessionId = PlaybackSessionId.of("concurrent-retry");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    return registry.tryMark(sourceId, sessionId);
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) {
                    winners++;
                }
            }
            assertEquals(1, winners);
            assertEquals(1, registry.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleCompletionCannotClearReplacementSession() {
        ClientMediaRetryRegistry registry = new ClientMediaRetryRegistry();
        PlaybackSourceId sourceId = PlaybackSourceId.of(UUID.randomUUID());
        PlaybackSessionId stale = PlaybackSessionId.of("retry-old");
        PlaybackSessionId replacement = PlaybackSessionId.of("retry-new");

        assertTrue(registry.tryMark(sourceId, stale));
        assertTrue(registry.forget(sourceId, stale));
        assertTrue(registry.tryMark(sourceId, replacement));

        assertFalse(registry.forget(sourceId, stale));
        assertTrue(registry.contains(sourceId, replacement));
    }

    @Test
    void acceptingReplacementClearsOnlyThatSourcesPendingRetries() {
        ClientMediaRetryRegistry registry = new ClientMediaRetryRegistry();
        PlaybackSourceId replacedSource = PlaybackSourceId.of(UUID.randomUUID());
        PlaybackSourceId otherSource = PlaybackSourceId.of(UUID.randomUUID());
        PlaybackSessionId first = PlaybackSessionId.of("retry-first");
        PlaybackSessionId second = PlaybackSessionId.of("retry-second");
        PlaybackSessionId other = PlaybackSessionId.of("retry-other");
        registry.tryMark(replacedSource, first);
        registry.tryMark(replacedSource, second);
        registry.tryMark(otherSource, other);

        registry.forgetSource(replacedSource);

        assertFalse(registry.contains(replacedSource, first));
        assertFalse(registry.contains(replacedSource, second));
        assertTrue(registry.contains(otherSource, other));
        assertEquals(1, registry.size());
    }

    @Test
    void retainedSessionRefreshClearsOnlyTheExactPendingRetry() {
        ClientMediaRetryRegistry registry = new ClientMediaRetryRegistry();
        PlaybackSourceId sourceId = PlaybackSourceId.of(UUID.randomUUID());
        PlaybackSessionId retained = PlaybackSessionId.of("retained-session");
        PlaybackSessionId other = PlaybackSessionId.of("other-session");
        registry.tryMark(sourceId, retained);
        registry.tryMark(sourceId, other);

        assertTrue(registry.forget(sourceId, retained));

        assertFalse(registry.contains(sourceId, retained));
        assertTrue(registry.contains(sourceId, other));
        assertEquals(1, registry.size());
    }

    @Test
    void clearedRetainedSessionCannotRunItsAlreadyQueuedDispatch() {
        ClientMediaRetryRegistry registry = new ClientMediaRetryRegistry();
        PlaybackSourceId sourceId = PlaybackSourceId.of(UUID.randomUUID());
        PlaybackSessionId retained = PlaybackSessionId.of("retained-session");
        PlaybackSessionId other = PlaybackSessionId.of("other-session");
        registry.tryMark(sourceId, retained);
        registry.tryMark(sourceId, other);
        registry.forget(sourceId, retained);
        boolean[] dispatched = { false };

        assertFalse(registry.dispatchIfPending(sourceId, retained, () -> dispatched[0] = true));

        assertFalse(dispatched[0]);
        assertTrue(registry.dispatchIfPending(sourceId, other, () -> true));
        assertTrue(registry.contains(sourceId, other));
    }
}
