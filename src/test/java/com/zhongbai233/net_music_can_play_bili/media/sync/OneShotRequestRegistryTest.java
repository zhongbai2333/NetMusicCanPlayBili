package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneShotRequestRegistryTest {
    @Test
    void sameMediaRequestsCanBeConsumedInReverseOrderWithoutContextSwap() {
        AtomicLong now = new AtomicLong(1_000L);
        ArrayDeque<String> tokens = new ArrayDeque<>();
        tokens.add("request-a");
        tokens.add("request-b");
        OneShotRequestRegistry<String> registry = new OneShotRequestRegistry<>(now::get, tokens::removeFirst);

        String firstToken = registry.register("session-a", 2_000L);
        String secondToken = registry.register("session-b", 2_000L);

        assertEquals("session-b", registry.consume(secondToken));
        assertEquals("session-a", registry.consume(firstToken));
    }

    @Test
    void tokenCanOnlyBeConsumedOnce() {
        OneShotRequestRegistry<String> registry = registryAt(1_000L, "single");
        String token = registry.register("context", 2_000L);

        assertEquals("context", registry.consume(token));
        assertNull(registry.consume(token));
        assertFalse(registry.contains(token));
    }

    @Test
    void expiredAndCancelledTokensAreUnavailable() {
        AtomicLong now = new AtomicLong(1_000L);
        ArrayDeque<String> tokens = new ArrayDeque<>();
        tokens.add("expired");
        tokens.add("cancelled");
        OneShotRequestRegistry<String> registry = new OneShotRequestRegistry<>(now::get, tokens::removeFirst);
        String expired = registry.register("old", 1_500L);
        String cancelled = registry.register("cancel", 3_000L);

        registry.cancel(cancelled);
        now.set(2_000L);

        assertNull(registry.consume(expired));
        assertNull(registry.consume(cancelled));
        assertFalse(registry.contains(expired));
        assertFalse(registry.contains(cancelled));
    }

    @Test
    void containsKeepsLiveTokenUntilConsumption() {
        OneShotRequestRegistry<String> registry = registryAt(1_000L, "live");
        String token = registry.register("context", 2_000L);

        assertTrue(registry.contains(token));
        assertEquals("context", registry.consume(token));
    }

    private static OneShotRequestRegistry<String> registryAt(long now, String token) {
        return new OneShotRequestRegistry<>(() -> now, () -> token);
    }
}