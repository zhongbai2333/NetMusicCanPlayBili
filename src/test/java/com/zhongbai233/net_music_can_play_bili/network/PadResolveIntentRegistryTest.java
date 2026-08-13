package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadResolveIntentRegistryTest {
    @Test
    void commandReplacementRejectsLateCompletionEvenForTheSamePoint() {
        PadResolveIntentRegistry registry = new PadResolveIntentRegistry();
        UUID ownerId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID pointId = UUID.randomUUID();
        PadResolveIntentRegistry.Intent start = registry.replaceCommand(ownerId, deviceId, pointId, 1, "BV1");
        PadResolveIntentRegistry.Intent seek = registry.replaceCommand(ownerId, deviceId, pointId, 1, "BV1");

        assertFalse(registry.isCurrent(deviceId, start));
        assertTrue(registry.isCurrent(deviceId, seek));
        registry.complete(deviceId, start);
        assertTrue(registry.isCurrent(deviceId, seek));
    }

    @Test
    void stopInvalidatesPendingResolve() {
        PadResolveIntentRegistry registry = new PadResolveIntentRegistry();
        UUID ownerId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        PadResolveIntentRegistry.Intent intent = registry.replaceCommand(ownerId, deviceId, UUID.randomUUID(), 2,
                "BV2");

        registry.invalidate(deviceId);

        assertFalse(registry.isCurrent(deviceId, intent));
    }

    @Test
    void retryCannotSupersedeAnExistingUserCommand() {
        PadResolveIntentRegistry registry = new PadResolveIntentRegistry();
        UUID ownerId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID pointId = UUID.randomUUID();
        PadResolveIntentRegistry.Intent seek = registry.replaceCommand(ownerId, deviceId, pointId, 3, "BV3");
        PlaybackSessionId sessionId = PadPlaybackSessionIds.create(deviceId, pointId, 7L);

        assertNull(registry.beginRetryIfIdle(ownerId, deviceId, pointId, 3, "BV3", sessionId));
        assertTrue(registry.isCurrent(deviceId, seek));
    }

    @Test
    void userCommandAlwaysInvalidatesAnAdmittedRetry() {
        PadResolveIntentRegistry registry = new PadResolveIntentRegistry();
        UUID ownerId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID pointId = UUID.randomUUID();
        PlaybackSessionId sessionId = PadPlaybackSessionIds.create(deviceId, pointId, 9L);
        PadResolveIntentRegistry.Intent retry = registry.beginRetryIfIdle(ownerId, deviceId, pointId, 4, "BV4",
                sessionId);

        assertNotNull(retry);
        assertTrue(retry.retry());
        PadResolveIntentRegistry.Intent restart = registry.replaceCommand(ownerId, deviceId, pointId, 4, "BV4");

        assertFalse(registry.isCurrent(deviceId, retry));
        assertTrue(registry.isCurrent(deviceId, restart));
    }

    @Test
    void retryRequiresSessionDeviceAndPointToMatch() {
        PadResolveIntentRegistry registry = new PadResolveIntentRegistry();
        UUID ownerId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID pointId = UUID.randomUUID();
        PlaybackSessionId anotherPoint = PadPlaybackSessionIds.create(deviceId, UUID.randomUUID(), 1L);

        assertNull(registry.beginRetryIfIdle(ownerId, deviceId, pointId, 1, "BV1", anotherPoint));
        assertNull(registry.beginRetryIfIdle(ownerId, deviceId, pointId, 1, "BV1",
                PlaybackSessionId.of("mp4-session")));
    }

    @Test
    void staleIntentCannotEnterItsFinalCommit() {
        PadResolveIntentRegistry registry = new PadResolveIntentRegistry();
        UUID ownerId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID pointId = UUID.randomUUID();
        PadResolveIntentRegistry.Intent stale = registry.replaceCommand(ownerId, deviceId, pointId, 1, "BV1");
        PadResolveIntentRegistry.Intent replacement = registry.replaceCommand(ownerId, deviceId, pointId, 1, "BV1");
        boolean[] ran = { false };

        assertFalse(registry.commitIfCurrent(deviceId, stale, () -> ran[0] = true));
        assertFalse(ran[0]);
        assertTrue(registry.isCurrent(deviceId, replacement));
    }

    @Test
    void successfulCommitConsumesOnlyItsOwnIntent() {
        PadResolveIntentRegistry registry = new PadResolveIntentRegistry();
        UUID ownerId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        PadResolveIntentRegistry.Intent intent = registry.replaceCommand(ownerId, deviceId, UUID.randomUUID(), 1,
                "BV1");

        assertTrue(registry.commitIfCurrent(deviceId, intent, () -> true));
        assertFalse(registry.isCurrent(deviceId, intent));
    }

    @Test
    void failedCommitStillReleasesItsIntent() {
        PadResolveIntentRegistry registry = new PadResolveIntentRegistry();
        UUID ownerId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        PadResolveIntentRegistry.Intent intent = registry.replaceCommand(ownerId, deviceId, UUID.randomUUID(), 1,
                "BV1");

        assertThrows(IllegalStateException.class,
                () -> registry.commitIfCurrent(deviceId, intent, () -> {
                    throw new IllegalStateException("commit failed");
                }));
        assertFalse(registry.isCurrent(deviceId, intent));
    }

    @Test
    void ownerLogoutInvalidatesAllOfItsDevicesOnly() {
        PadResolveIntentRegistry registry = new PadResolveIntentRegistry();
        UUID ownerId = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID();
        UUID firstDevice = UUID.randomUUID();
        UUID secondDevice = UUID.randomUUID();
        UUID otherDevice = UUID.randomUUID();
        PadResolveIntentRegistry.Intent first = registry.replaceCommand(ownerId, firstDevice, UUID.randomUUID(), 1,
                "BV1");
        PadResolveIntentRegistry.Intent second = registry.replaceCommand(ownerId, secondDevice, UUID.randomUUID(), 2,
                "BV2");
        PadResolveIntentRegistry.Intent other = registry.replaceCommand(otherOwnerId, otherDevice, UUID.randomUUID(),
                3, "BV3");

        registry.invalidateOwner(ownerId);

        assertFalse(registry.isCurrent(firstDevice, first));
        assertFalse(registry.isCurrent(secondDevice, second));
        assertTrue(registry.isCurrent(otherDevice, other));
    }
}
