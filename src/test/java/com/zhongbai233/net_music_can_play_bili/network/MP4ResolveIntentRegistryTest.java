package com.zhongbai233.net_music_can_play_bili.network;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Resolve ownership across manual commands, discovery and queue mutations. */
class MP4ResolveIntentRegistryTest {

    @Test
    void aNewIntentRejectsAndCannotCompleteTheOlderOne() {
        MP4ResolveIntentRegistry registry = new MP4ResolveIntentRegistry();
        UUID deviceId = UUID.randomUUID();
        MP4ResolveIntentRegistry.Intent first = registry.replace(deviceId, 1, "BV1|p=2");
        MP4ResolveIntentRegistry.Intent second = registry.replace(deviceId, 2, "BV1|p=2");

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
        MP4ResolveIntentRegistry.Intent pending = registry.beginIfIdle(deviceId, 0, "BV1|p=1");

        registry.invalidate(deviceId);

        assertFalse(registry.isCurrent(deviceId, pending));
        assertNotNull(registry.beginIfIdle(deviceId, 0, "BV1|p=1"));
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
        assertTrue(registry.hasPending(deviceId));
        org.junit.jupiter.api.Assertions.assertEquals(2, registry.pendingQueueIndex(deviceId, 0));

        registry.complete(deviceId, seek);
        assertNotNull(registry.beginIfIdle(deviceId, 0, "BV1|p=1"));
    }

    private final MP4ResolveIntentRegistry intents = new MP4ResolveIntentRegistry();

    @Test void rapidNextUsesLatestPendingIndexAndStaleCallbackCannotClearIt() {
        UUID id = UUID.randomUUID();
        var first = intents.replace(id, 1, "B");
        var second = intents.replace(id,
            MP4PlaybackQueuePolicy.manualMove(intents.pendingQueueIndex(id, 0), 3, 1, false, 0), "C");
        try {
            intents.complete(id, first);
            assertEquals(2, second.queueIndex());
            assertTrue(intents.isCurrent(id, second));
            assertTrue(intents.hasPending(id));
        } finally { intents.complete(id, second); }
    }

    @Test void removedPendingDuplicateCannotStartAnotherOccurrenceAtTheSameIndex() {
        UUID id = UUID.randomUUID();
        var command = intents.replace(id, 2, "A");
        intents.onQueueRemoval(id, 2);
        assertFalse(intents.isCurrent(id, command));
        assertFalse(intents.hasPending(id));
    }
    @Test void removingBeforePendingOccurrenceCancelsItsCapturedIndex() {
        UUID id = UUID.randomUUID();
        var command = intents.replace(id, 2, "A");
        intents.onQueueRemoval(id, 0);
        assertFalse(intents.isCurrent(id, command));
    }
    @Test void appendOrRemovingFollowingItemDoesNotCancelPendingOccurrence() {
        UUID id = UUID.randomUUID();
        var command = intents.replace(id, 2, "A");
        try {
            intents.onQueueRemoval(id, -1);
            intents.onQueueRemoval(id, 3);
            assertTrue(intents.isCurrent(id, command));
        } finally { intents.complete(id, command); }
    }
}
