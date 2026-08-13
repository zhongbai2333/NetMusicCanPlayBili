package com.zhongbai233.net_music_can_play_bili.server;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;

/** 每玩家独立的中控台媒体消费租约；不同玩家不会互相排斥。 */
public final class ControlConsoleConsumerLeaseRegistry {
    public static final long LEASE_MILLIS = 3_000L;
    private static final Map<Key, Lease> LEASES = new HashMap<>();

    private ControlConsoleConsumerLeaseRegistry() {
    }

    public static synchronized UUID acquireOrRenew(Key key, long nowMillis) {
        Objects.requireNonNull(key, "key");
        Lease current = LEASES.get(key);
        if (current != null && current.expiresAtMillis() > nowMillis) {
            LEASES.put(key, new Lease(current.leaseId(), expiresAt(nowMillis)));
            return current.leaseId();
        }
        UUID leaseId = UUID.randomUUID();
        LEASES.put(key, new Lease(leaseId, expiresAt(nowMillis)));
        return leaseId;
    }

    public static synchronized boolean renew(Key key, UUID leaseId, long nowMillis) {
        Lease current = LEASES.get(key);
        if (current == null || current.expiresAtMillis() <= nowMillis || !current.leaseId().equals(leaseId)) {
            return false;
        }
        LEASES.put(key, new Lease(current.leaseId(), expiresAt(nowMillis)));
        return true;
    }

    public static synchronized boolean validate(Key key, UUID leaseId, long nowMillis) {
        Lease lease = LEASES.get(key);
        return lease != null && lease.expiresAtMillis() > nowMillis && lease.leaseId().equals(leaseId);
    }

    public static synchronized boolean hasActive(Key key, long nowMillis) {
        Lease lease = LEASES.get(key);
        return lease != null && lease.expiresAtMillis() > nowMillis;
    }

    public static synchronized void release(Key key, UUID leaseId) {
        Lease lease = LEASES.get(key);
        if (lease != null && lease.leaseId().equals(leaseId)) {
            LEASES.remove(key);
        }
    }

    public static synchronized void releasePlayer(UUID playerId) {
        LEASES.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public static synchronized void cleanupExpired(long nowMillis) {
        LEASES.values().removeIf(lease -> lease.expiresAtMillis() <= nowMillis);
    }

    public static synchronized void clear() {
        LEASES.clear();
    }

    /** Read-only system-test/diagnostic view of active consumers for one exact console. */
    public static synchronized Set<UUID> activePlayers(String dimension, long packedPos, long nowMillis) {
        Objects.requireNonNull(dimension, "dimension");
        return LEASES.entrySet().stream()
                .filter(entry -> entry.getKey().dimension().equals(dimension)
                        && entry.getKey().packedPos() == packedPos
                        && entry.getValue().expiresAtMillis() > nowMillis)
                .map(entry -> entry.getKey().playerId())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static long expiresAt(long nowMillis) {
        return nowMillis > Long.MAX_VALUE - LEASE_MILLIS ? Long.MAX_VALUE : nowMillis + LEASE_MILLIS;
    }

    public record Key(String dimension, long packedPos, UUID playerId) {
        public Key {
            dimension = Objects.requireNonNull(dimension, "dimension");
            playerId = Objects.requireNonNull(playerId, "playerId");
            if (dimension.isBlank()) {
                throw new IllegalArgumentException("dimension must not be blank");
            }
        }
    }

    private record Lease(UUID leaseId, long expiresAtMillis) {
    }
}
