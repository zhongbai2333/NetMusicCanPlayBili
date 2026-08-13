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
        ArrayDeque<MediaRequestToken> tokens = new ArrayDeque<>();
        tokens.add(new MediaRequestToken("request-a"));
        tokens.add(new MediaRequestToken("request-b"));
        OneShotRequestRegistry<String> registry = new OneShotRequestRegistry<>(now::get, tokens::removeFirst);

        MediaRequestToken firstToken = registry.registerToken("session-a", 2_000L);
        MediaRequestToken secondToken = registry.registerToken("session-b", 2_000L);

        assertEquals("session-b", registry.consumeToken(secondToken));
        assertEquals("session-a", registry.consumeToken(firstToken));
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
        ArrayDeque<MediaRequestToken> tokens = new ArrayDeque<>();
        tokens.add(new MediaRequestToken("expired"));
        tokens.add(new MediaRequestToken("cancelled"));
        OneShotRequestRegistry<String> registry = new OneShotRequestRegistry<>(now::get, tokens::removeFirst);
        MediaRequestToken expired = registry.registerToken("old", 1_500L);
        MediaRequestToken cancelled = registry.registerToken("cancel", 3_000L);

        registry.cancelToken(cancelled);
        now.set(2_000L);

        assertNull(registry.consumeToken(expired));
        assertNull(registry.consumeToken(cancelled));
        assertFalse(registry.containsToken(expired));
        assertFalse(registry.containsToken(cancelled));
    }

    @Test
    void containsKeepsLiveTokenUntilConsumption() {
        OneShotRequestRegistry<String> registry = registryAt(1_000L, "live");
        MediaRequestToken token = registry.registerToken("context", 2_000L);

        assertTrue(registry.containsToken(token));
        assertEquals("context", registry.consumeToken(token));
    }

    private static OneShotRequestRegistry<String> registryAt(long now, String token) {
        return new OneShotRequestRegistry<>(() -> now, () -> new MediaRequestToken(token));
    }
}
