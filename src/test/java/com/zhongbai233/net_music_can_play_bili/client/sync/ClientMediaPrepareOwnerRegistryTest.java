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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMediaPrepareOwnerRegistryTest {
    @Test
    void concurrentPrepareAdmissionHasExactlyOneOwner() throws Exception {
        ClientMediaPrepareOwnerRegistry registry = new ClientMediaPrepareOwnerRegistry();
        ClientMediaPrepareOwnerRegistry.Key key = key("prepare-session", false);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    return registry.tryRegister(key, new TestOwner());
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
    void replacementCancelsPreviousAndRejectsStaleExactRemoval() {
        ClientMediaPrepareOwnerRegistry registry = new ClientMediaPrepareOwnerRegistry();
        ClientMediaPrepareOwnerRegistry.Key key = key("lyric-session", false);
        TestOwner stale = new TestOwner();
        TestOwner replacement = new TestOwner();
        assertTrue(registry.tryRegister(key, stale));

        registry.replace(key, replacement);

        assertEquals(1, stale.cancellations());
        assertEquals(0, replacement.cancellations());
        assertFalse(registry.remove(key, stale));
        assertTrue(registry.contains(key));
        assertTrue(registry.remove(key, replacement));
        assertEquals(0, registry.size());
    }

    @Test
    void acceptingReplacementSessionCancelsOnlyThatSourcesTasks() {
        ClientMediaPrepareOwnerRegistry registry = new ClientMediaPrepareOwnerRegistry();
        PlaybackSourceId replacedSource = PlaybackSourceId.of(UUID.randomUUID());
        PlaybackSourceId otherSource = PlaybackSourceId.of(UUID.randomUUID());
        TestOwner prepare = new TestOwner();
        TestOwner lyrics = new TestOwner();
        TestOwner other = new TestOwner();
        registry.tryRegister(new ClientMediaPrepareOwnerRegistry.Key(replacedSource,
                PlaybackSessionId.of("prepare-old"), false), prepare);
        registry.tryRegister(new ClientMediaPrepareOwnerRegistry.Key(replacedSource,
                PlaybackSessionId.of("lyrics-old"), true), lyrics);
        ClientMediaPrepareOwnerRegistry.Key otherKey = new ClientMediaPrepareOwnerRegistry.Key(otherSource,
                PlaybackSessionId.of("prepare-other"), false);
        registry.tryRegister(otherKey, other);

        registry.cancelSource(replacedSource);

        assertEquals(1, prepare.cancellations());
        assertEquals(1, lyrics.cancellations());
        assertEquals(0, other.cancellations());
        assertTrue(registry.contains(otherKey));
        assertEquals(1, registry.size());
    }

    @Test
    void clearCancelsEveryRemainingOwnerExactlyOnce() {
        ClientMediaPrepareOwnerRegistry registry = new ClientMediaPrepareOwnerRegistry();
        TestOwner first = new TestOwner();
        TestOwner second = new TestOwner();
        registry.tryRegister(key("clear-first", false), first);
        registry.tryRegister(key("clear-second", true), second);

        registry.clear();
        registry.clear();

        assertEquals(1, first.cancellations());
        assertEquals(1, second.cancellations());
        assertEquals(0, registry.size());
    }

    private static ClientMediaPrepareOwnerRegistry.Key key(String sessionId, boolean headphoneRouted) {
        return new ClientMediaPrepareOwnerRegistry.Key(PlaybackSourceId.of(UUID.randomUUID()),
                PlaybackSessionId.of(sessionId), headphoneRouted);
    }

    private static final class TestOwner implements ClientMediaPrepareOwnerRegistry.Owner {
        private final AtomicInteger cancellations = new AtomicInteger();

        @Override
        public void cancel() {
            cancellations.incrementAndGet();
        }

        int cancellations() {
            return cancellations.get();
        }
    }
}
