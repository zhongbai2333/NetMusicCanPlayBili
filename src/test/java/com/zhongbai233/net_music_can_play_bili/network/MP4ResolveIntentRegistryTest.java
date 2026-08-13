package com.zhongbai233.net_music_can_play_bili.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MP4ResolveIntentRegistryTest {
    @Test
    void deduplicatesTheSameDiscoveryWhileItIsPending() {
        MP4ResolveIntentRegistry registry = new MP4ResolveIntentRegistry();
        UUID deviceId = UUID.randomUUID();

        MP4ResolveIntentRegistry.Intent first = registry.begin(deviceId, 1, "BV1|p=2");

        assertNotNull(first);
        assertNull(registry.begin(deviceId, 1, "BV1|p=2"));
        assertTrue(registry.isCurrent(deviceId, first));
    }

    @Test
    void aNewIntentRejectsAndCannotCompleteTheOlderOne() {
        MP4ResolveIntentRegistry registry = new MP4ResolveIntentRegistry();
        UUID deviceId = UUID.randomUUID();
        MP4ResolveIntentRegistry.Intent first = registry.begin(deviceId, 1, "BV1|p=2");
        MP4ResolveIntentRegistry.Intent second = registry.begin(deviceId, 2, "BV1|p=2");

        assertNotNull(second);
        assertFalse(registry.isCurrent(deviceId, first));
        assertTrue(registry.isCurrent(deviceId, second));

        registry.complete(deviceId, first);
        assertTrue(registry.isCurrent(deviceId, second));

        registry.complete(deviceId, second);
        assertFalse(registry.isCurrent(deviceId, second));
    }

    @Test
    void stopInvalidatesThePendingIntentEvenForTheSameSong() {
        MP4ResolveIntentRegistry registry = new MP4ResolveIntentRegistry();
        UUID deviceId = UUID.randomUUID();
        MP4ResolveIntentRegistry.Intent pending = registry.begin(deviceId, 0, "BV1|p=1");

        registry.invalidate(deviceId);

        assertFalse(registry.isCurrent(deviceId, pending));
        assertNotNull(registry.begin(deviceId, 0, "BV1|p=1"));
    }

    @Test
    void aManualCommandAlwaysReplacesTheSamePendingSong() {
        MP4ResolveIntentRegistry registry = new MP4ResolveIntentRegistry();
        UUID deviceId = UUID.randomUUID();
        MP4ResolveIntentRegistry.Intent first = registry.replace(deviceId, 0, "BV1|p=1");
        MP4ResolveIntentRegistry.Intent seek = registry.replace(deviceId, 0, "BV1|p=1");

        assertFalse(registry.isCurrent(deviceId, first));
        assertTrue(registry.isCurrent(deviceId, seek));
    }

    @Test
    void lowerPriorityRetryCannotReplaceAnExistingCommandIntent() {
        MP4ResolveIntentRegistry registry = new MP4ResolveIntentRegistry();
        UUID deviceId = UUID.randomUUID();
        MP4ResolveIntentRegistry.Intent seek = registry.replace(deviceId, 2, "BV2|p=1");

        assertNull(registry.beginIfIdle(deviceId, 0, "BV1|p=1"));
        assertTrue(registry.isCurrent(deviceId, seek));

        registry.complete(deviceId, seek);
        assertNotNull(registry.beginIfIdle(deviceId, 0, "BV1|p=1"));
    }
}
