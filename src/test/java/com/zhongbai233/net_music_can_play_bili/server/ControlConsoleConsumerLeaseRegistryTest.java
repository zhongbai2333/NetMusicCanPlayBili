package com.zhongbai233.net_music_can_play_bili.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleConsumerLeaseRegistryTest {
    @AfterEach
    void clear() {
        ControlConsoleConsumerLeaseRegistry.clear();
    }

    @Test
    void playersReceiveIndependentRenewableLeases() {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        var firstKey = key(firstPlayer);
        var secondKey = key(secondPlayer);
        UUID first = ControlConsoleConsumerLeaseRegistry.acquireOrRenew(firstKey, 1_000L);
        UUID renewed = ControlConsoleConsumerLeaseRegistry.acquireOrRenew(firstKey, 2_000L);
        UUID second = ControlConsoleConsumerLeaseRegistry.acquireOrRenew(secondKey, 2_000L);

        assertEquals(first, renewed);
        assertNotEquals(first, second);
        assertTrue(ControlConsoleConsumerLeaseRegistry.renew(firstKey, first, 2_500L));
        assertFalse(ControlConsoleConsumerLeaseRegistry.renew(firstKey, UUID.randomUUID(), 2_500L));
        assertTrue(ControlConsoleConsumerLeaseRegistry.validate(firstKey, first, 2_001L));
        assertTrue(ControlConsoleConsumerLeaseRegistry.validate(secondKey, second, 2_001L));
    }

    @Test
    void expiryReleaseAndLogoutInvalidateLeases() {
        UUID player = UUID.randomUUID();
        var key = key(player);
        UUID expired = ControlConsoleConsumerLeaseRegistry.acquireOrRenew(key, 1_000L);
        assertFalse(ControlConsoleConsumerLeaseRegistry.validate(key, expired,
                1_000L + ControlConsoleConsumerLeaseRegistry.LEASE_MILLIS));
        UUID current = ControlConsoleConsumerLeaseRegistry.acquireOrRenew(key, 5_000L);
        ControlConsoleConsumerLeaseRegistry.release(key, current);
        assertFalse(ControlConsoleConsumerLeaseRegistry.validate(key, current, 5_001L));
        ControlConsoleConsumerLeaseRegistry.acquireOrRenew(key, 6_000L);
        ControlConsoleConsumerLeaseRegistry.releasePlayer(player);
        assertFalse(ControlConsoleConsumerLeaseRegistry.hasActive(key, 6_001L));
    }

    private static ControlConsoleConsumerLeaseRegistry.Key key(UUID playerId) {
        return new ControlConsoleConsumerLeaseRegistry.Key("minecraft:overworld", 42L, playerId);
    }
}