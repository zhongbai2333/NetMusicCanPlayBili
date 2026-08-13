package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class ClientPlaybackSessionTest {
    @Test
    void followsPlaybackLifecycle() {
        ClientPlaybackSession session = new ClientPlaybackSession("session", 10_000L, 1_000L);

        assertEquals(PlaybackSessionId.of("session"), session.playbackSessionId());
        assertEquals(ClientPlaybackSession.State.PREPARING, session.state());
        assertTrue(session.transitionTo(ClientPlaybackSession.State.BUFFERING));
        assertTrue(session.transitionTo(ClientPlaybackSession.State.PLAYING));
        assertTrue(session.transitionTo(ClientPlaybackSession.State.RECOVERING));
        assertTrue(session.transitionTo(ClientPlaybackSession.State.BUFFERING));
        assertTrue(session.transitionTo(ClientPlaybackSession.State.PLAYING));
    }

    @Test
    void cancellationIsIdempotentAndClosesLateResources() {
        ClientPlaybackSession session = new ClientPlaybackSession("session", 10_000L, 1_000L);
        AtomicInteger closed = new AtomicInteger();
        session.onCancel(closed::incrementAndGet);

        assertTrue(session.cancel());
        assertFalse(session.cancel());
        session.onCancel(closed::incrementAndGet);

        assertEquals(2, closed.get());
        assertEquals(ClientPlaybackSession.State.STOPPED, session.state());
        assertTrue(session.isCancelled());
    }

    @Test
    void rejectsInvalidOrTerminalTransitions() {
        ClientPlaybackSession session = new ClientPlaybackSession("session", 10_000L, 1_000L);

        assertFalse(session.transitionTo(ClientPlaybackSession.State.RECOVERING));
        assertTrue(session.fail());
        assertFalse(session.transitionTo(ClientPlaybackSession.State.PLAYING));
        assertFalse(session.cancel() && session.state() != ClientPlaybackSession.State.FAILED);
    }

    @Test
    void concurrentCancellationRunsResourcesExactlyOnce() throws Exception {
        ClientPlaybackSession session = new ClientPlaybackSession("session", 10_000L, 1_000L);
        AtomicInteger closed = new AtomicInteger();
        for (int i = 0; i < 100; i++) {
            session.onCancel(closed::incrementAndGet);
        }
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return session.cancel();
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    winners++;
                }
            }
            assertEquals(1, winners);
            assertEquals(100, closed.get());
            assertEquals(ClientPlaybackSession.State.STOPPED, session.state());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentLateBindingStillRunsEveryResourceOnce() throws Exception {
        ClientPlaybackSession session = new ClientPlaybackSession("session", 10_000L, 1_000L);
        AtomicInteger closed = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                tasks.add(executor.submit(() -> {
                    start.await();
                    session.onCancel(closed::incrementAndGet);
                    return null;
                }));
            }
            tasks.add(executor.submit(() -> {
                start.await();
                session.cancel();
                return null;
            }));
            start.countDown();
            for (Future<?> task : tasks) {
                task.get();
            }
            assertEquals(100, closed.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failingCleanupDoesNotBlockRemainingResources() {
        ClientPlaybackSession session = new ClientPlaybackSession("session", 10_000L, 1_000L);
        AtomicInteger closed = new AtomicInteger();
        session.onCancel(() -> {
            throw new IllegalStateException("expected test failure");
        });
        session.onCancel(closed::incrementAndGet);

        assertTrue(session.cancel());
        assertEquals(1, closed.get());
    }

    @Test
    void failedSessionStillReleasesBoundResources() {
        ClientPlaybackSession session = new ClientPlaybackSession("session", 10_000L, 1_000L);
        AtomicInteger closed = new AtomicInteger();
        session.onCancel(closed::incrementAndGet);

        assertTrue(session.fail());
        assertTrue(session.cancel());
        assertEquals(ClientPlaybackSession.State.FAILED, session.state());
        assertEquals(1, closed.get());
    }

    @Test
    void replacingNamedResourceCancelsPreviousOwner() {
        ClientPlaybackSession session = new ClientPlaybackSession("session", 10_000L, 1_000L);
        AtomicInteger firstClosed = new AtomicInteger();
        AtomicInteger secondClosed = new AtomicInteger();

        assertTrue(session.replaceResource("video-resolve", firstClosed::incrementAndGet));
        assertTrue(session.replaceResource("video-resolve", secondClosed::incrementAndGet));
        assertEquals(1, firstClosed.get());
        assertEquals(0, secondClosed.get());

        assertTrue(session.cancel());
        assertEquals(1, firstClosed.get());
        assertEquals(1, secondClosed.get());
    }

    @Test
    void lateNamedResourceIsClosedImmediately() {
        ClientPlaybackSession session = new ClientPlaybackSession("session", 10_000L, 1_000L);
        AtomicInteger closed = new AtomicInteger();

        assertTrue(session.cancel());
        assertFalse(session.replaceResource("decoder", closed::incrementAndGet));
        assertEquals(1, closed.get());
    }

    @Test
    void concurrentNamedReplaceAndCancelClosesBothResourcesExactlyOnce() throws Exception {
        ClientPlaybackSession session = new ClientPlaybackSession("session", 10_000L, 1_000L);
        AtomicInteger firstClosed = new AtomicInteger();
        AtomicInteger secondClosed = new AtomicInteger();
        assertTrue(session.replaceResource("video-resolve", firstClosed::incrementAndGet));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> replace = executor.submit(() -> {
                start.await();
                session.replaceResource("video-resolve", secondClosed::incrementAndGet);
                return null;
            });
            Future<?> cancel = executor.submit(() -> {
                start.await();
                session.cancel();
                return null;
            });
            start.countDown();
            replace.get();
            cancel.get();
            assertEquals(1, firstClosed.get());
            assertEquals(1, secondClosed.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
