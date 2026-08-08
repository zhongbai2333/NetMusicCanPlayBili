package com.zhongbai233.net_music_can_play_bili.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 服务端内存中的中控台独占编辑租约；不进入世界存档。 */
public final class ControlConsoleEditLeaseRegistry {
    public static final long LEASE_MILLIS = 10_000L;
    private static final Map<Key, Lease> LEASES = new HashMap<>();

    private ControlConsoleEditLeaseRegistry() {
    }

    public static synchronized AcquireResult acquire(Key key, UUID playerId, long nowMillis) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(playerId, "playerId");
        Lease current = LEASES.get(key);
        if (current != null && current.expiresAtMillis() > nowMillis) {
            if (!current.playerId().equals(playerId)) {
                return new AcquireResult(Status.BUSY, null, current.playerId());
            }
            Lease renewed = current.renewed(nowMillis);
            LEASES.put(key, renewed);
            return new AcquireResult(Status.GRANTED, renewed.leaseId(), playerId);
        }
        Lease granted = new Lease(UUID.randomUUID(), playerId, expiresAt(nowMillis));
        LEASES.put(key, granted);
        return new AcquireResult(Status.GRANTED, granted.leaseId(), playerId);
    }

    public static synchronized boolean renew(Key key, UUID playerId, UUID leaseId, long nowMillis) {
        Lease current = LEASES.get(key);
        if (!matches(current, playerId, leaseId, nowMillis)) {
            return false;
        }
        LEASES.put(key, current.renewed(nowMillis));
        return true;
    }

    public static synchronized boolean validate(Key key, UUID playerId, UUID leaseId, long nowMillis) {
        return matches(LEASES.get(key), playerId, leaseId, nowMillis);
    }

    public static synchronized void release(Key key, UUID playerId, UUID leaseId) {
        Lease current = LEASES.get(key);
        if (current != null && current.playerId().equals(playerId) && current.leaseId().equals(leaseId)) {
            LEASES.remove(key);
        }
    }

    public static synchronized void releasePlayer(UUID playerId) {
        LEASES.values().removeIf(lease -> lease.playerId().equals(playerId));
    }

    public static synchronized void cleanupExpired(long nowMillis) {
        Iterator<Lease> iterator = LEASES.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expiresAtMillis() <= nowMillis) {
                iterator.remove();
            }
        }
    }

    public static synchronized void clear() {
        LEASES.clear();
    }

    private static boolean matches(Lease lease, UUID playerId, UUID leaseId, long nowMillis) {
        return lease != null && lease.expiresAtMillis() > nowMillis
                && lease.playerId().equals(playerId) && lease.leaseId().equals(leaseId);
    }

    private static long expiresAt(long nowMillis) {
        return nowMillis > Long.MAX_VALUE - LEASE_MILLIS ? Long.MAX_VALUE : nowMillis + LEASE_MILLIS;
    }

    public record Key(String dimension, long packedPos) {
        public Key {
            dimension = Objects.requireNonNull(dimension, "dimension");
            if (dimension.isBlank()) {
                throw new IllegalArgumentException("dimension must not be blank");
            }
        }
    }

    public enum Status { GRANTED, BUSY }

    public record AcquireResult(Status status, UUID leaseId, UUID holderId) {
    }

    private record Lease(UUID leaseId, UUID playerId, long expiresAtMillis) {
        private Lease renewed(long nowMillis) {
            return new Lease(leaseId, playerId, expiresAt(nowMillis));
        }
    }
}