package com.zhongbai233.net_music_can_play_bili.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleEditLeaseRegistryTest {
    private static final ControlConsoleEditLeaseRegistry.Key KEY =
            new ControlConsoleEditLeaseRegistry.Key("minecraft:overworld", 123456789L);

    @AfterEach
    void clear() {
        ControlConsoleEditLeaseRegistry.clear();
    }

    @Test
    void leaseIsExclusiveRenewableAndReusableByHolder() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var first = ControlConsoleEditLeaseRegistry.acquire(KEY, owner, 1_000L);
        var repeated = ControlConsoleEditLeaseRegistry.acquire(KEY, owner, 2_000L);
        var busy = ControlConsoleEditLeaseRegistry.acquire(KEY, other, 2_000L);

        assertEquals(ControlConsoleEditLeaseRegistry.Status.GRANTED, first.status());
        assertEquals(first.leaseId(), repeated.leaseId());
        assertEquals(ControlConsoleEditLeaseRegistry.Status.BUSY, busy.status());
        assertTrue(ControlConsoleEditLeaseRegistry.renew(KEY, owner, first.leaseId(), 3_000L));
        assertTrue(ControlConsoleEditLeaseRegistry.validate(KEY, owner, first.leaseId(), 3_001L));
        assertFalse(ControlConsoleEditLeaseRegistry.validate(KEY, other, first.leaseId(), 3_001L));
    }

    @Test
    void expiryReleaseAndLogoutAllowAnotherEditor() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var expired = ControlConsoleEditLeaseRegistry.acquire(KEY, owner, 1_000L);
        assertFalse(ControlConsoleEditLeaseRegistry.validate(KEY, owner, expired.leaseId(),
                1_000L + ControlConsoleEditLeaseRegistry.LEASE_MILLIS));
        var replacement = ControlConsoleEditLeaseRegistry.acquire(KEY, other,
                1_000L + ControlConsoleEditLeaseRegistry.LEASE_MILLIS);
        ControlConsoleEditLeaseRegistry.releasePlayer(other);
        var reclaimed = ControlConsoleEditLeaseRegistry.acquire(KEY, owner, 20_000L);
        ControlConsoleEditLeaseRegistry.release(KEY, owner, reclaimed.leaseId());

        assertEquals(ControlConsoleEditLeaseRegistry.Status.GRANTED, replacement.status());
        assertEquals(ControlConsoleEditLeaseRegistry.Status.GRANTED, reclaimed.status());
        assertFalse(ControlConsoleEditLeaseRegistry.validate(KEY, owner, reclaimed.leaseId(), 20_001L));
    }
}