package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AiSubtitleSessionRegistryTest {
    @Test
    void consumersShareOneSourceSessionTaskAndLastReleaseCancelsExactlyOnce() {
        Loader loader = new Loader();
        AiSubtitleSessionRegistry<String, String, String> registry = new AiSubtitleSessionRegistry<>(loader);
        PlaybackSessionId session = PlaybackSessionId.of("session-a");

        registry.acquire("console-a", "turntable", session, "BV1", "title");
        registry.acquire("console-b", "turntable", session, "BV1", "title");

        assertEquals(1, loader.loads.get());
        assertEquals(1, registry.activeSessions());
        assertEquals(2, registry.activeConsumers());
        registry.release("console-a");
        assertEquals(0, loader.latest().cancels.get());
        registry.release("console-b");
        assertEquals(1, loader.latest().cancels.get());
        assertEquals(0, registry.activeSessions());
        assertEquals(0, registry.activeConsumers());
    }

    @Test
    void sessionReplacementCancelsOldOwnerAndLateCompletionCannotOverwriteTheNewSession() {
        Loader loader = new Loader();
        AiSubtitleSessionRegistry<String, String, String> registry = new AiSubtitleSessionRegistry<>(loader);
        PlaybackSessionId oldSession = PlaybackSessionId.of("old-session");
        PlaybackSessionId newSession = PlaybackSessionId.of("new-session");
        registry.acquire("console", "turntable", oldSession, "BV-old", "old");
        ManualTask<String> oldTask = loader.latest();

        registry.acquire("console", "turntable", newSession, "BV-new", "new");
        ManualTask<String> newTask = loader.latest();

        assertEquals(1, oldTask.cancels.get());
        oldTask.future.complete("stale");
        assertEquals(AiSubtitleSessionRegistry.Status.UNAVAILABLE,
                registry.snapshot("turntable", oldSession).status());
        assertEquals(AiSubtitleSessionRegistry.Status.LOADING,
                registry.snapshot("turntable", newSession).status());

        newTask.future.complete("fresh");
        var snapshot = registry.snapshot("turntable", newSession);
        assertEquals(AiSubtitleSessionRegistry.Status.READY, snapshot.status());
        assertEquals("fresh", snapshot.result());
    }

    @Test
    void nullResultIsUnavailableAndFailureIsDistinguishedForSafeFallback() {
        Loader loader = new Loader();
        AiSubtitleSessionRegistry<String, String, String> registry = new AiSubtitleSessionRegistry<>(loader);
        PlaybackSessionId unavailable = PlaybackSessionId.of("unavailable");
        registry.acquire("first", "turntable", unavailable, "BV1", "title");
        loader.latest().future.complete(null);

        var noTrack = registry.snapshot("turntable", unavailable);
        assertEquals(AiSubtitleSessionRegistry.Status.UNAVAILABLE, noTrack.status());
        assertNull(noTrack.result());

        PlaybackSessionId failed = PlaybackSessionId.of("failed");
        registry.acquire("second", "turntable", failed, "BV2", "title");
        loader.latest().future.completeExceptionally(new IllegalStateException("network down"));
        var failure = registry.snapshot("turntable", failed);
        assertEquals(AiSubtitleSessionRegistry.Status.FAILED, failure.status());
        assertFalse(failure.failureReason().isBlank());
    }

    @Test
    void idempotentAcquireDoesNotRestartAReadyOrPendingTask() {
        Loader loader = new Loader();
        AiSubtitleSessionRegistry<String, String, String> registry = new AiSubtitleSessionRegistry<>(loader);
        PlaybackSessionId session = PlaybackSessionId.of("same-session");
        registry.acquire("console", "turntable", session, "BV1", "first title");
        ManualTask<String> task = loader.latest();

        registry.acquire("console", "turntable", session, "BV1", "updated title");
        assertEquals(1, loader.loads.get());
        task.future.complete("line");
        registry.acquire("console", "turntable", session, "BV1", "third title");

        assertEquals(1, loader.loads.get());
        assertSame(task, loader.latest());
        assertEquals("line", registry.snapshot("turntable", session).result());
    }

    @Test
    void clearCancelsEveryIndependentSession() {
        Loader loader = new Loader();
        AiSubtitleSessionRegistry<String, String, String> registry = new AiSubtitleSessionRegistry<>(loader);
        registry.acquire("a", "source-a", PlaybackSessionId.of("session-a"), "BV1", "a");
        ManualTask<String> first = loader.latest();
        registry.acquire("b", "source-b", PlaybackSessionId.of("session-b"), "BV2", "b");
        ManualTask<String> second = loader.latest();

        registry.clear();

        assertEquals(1, first.cancels.get());
        assertEquals(1, second.cancels.get());
        assertEquals(0, registry.activeSessions());
        assertEquals(0, registry.activeConsumers());
    }

    private static final class Loader implements AiSubtitleSessionRegistry.Loader<String> {
        private final AtomicInteger loads = new AtomicInteger();
        private final ArrayDeque<ManualTask<String>> tasks = new ArrayDeque<>();

        @Override
        public AiSubtitleSessionRegistry.Task<String> load(String rawUrl, String title) {
            loads.incrementAndGet();
            ManualTask<String> task = new ManualTask<>();
            tasks.addLast(task);
            return task;
        }

        private ManualTask<String> latest() {
            return tasks.getLast();
        }
    }

    private static final class ManualTask<R> implements AiSubtitleSessionRegistry.Task<R> {
        private final CompletableFuture<R> future = new CompletableFuture<>();
        private final AtomicInteger cancels = new AtomicInteger();

        @Override
        public CompletableFuture<R> future() {
            return future;
        }

        @Override
        public void cancel() {
            cancels.incrementAndGet();
        }
    }
}
