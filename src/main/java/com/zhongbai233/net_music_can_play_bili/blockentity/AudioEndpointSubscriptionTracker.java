package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackApproachPredictor;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-player, per-source spatial subscriptions with revision-based endpoint deltas. */
final class AudioEndpointSubscriptionTracker {
    static final int CELL_SIZE_BLOCKS = 32;
    static final double DISCOVERY_LEAD_BLOCKS = PlaybackApproachPredictor.MAX_LEAD_BLOCKS;

    private final Map<Key, State> states = new HashMap<>();
    private final Map<Key, Long> generations = new HashMap<>();

    Update update(UUID playerId, PlaybackSourceId sourceId, String sessionId, long sourcePos,
            double playerX, double playerY, double playerZ, int sourceRange, List<Endpoint> endpoints) {
        return update(playerId, sourceId, sessionId, sourcePos, playerX, playerY, playerZ, sourceRange,
                snapshot(endpoints));
    }

    synchronized Update update(UUID playerId, PlaybackSourceId sourceId, String sessionId, long sourcePos,
            double playerX, double playerY, double playerZ, int sourceRange, SpatialSnapshot endpoints) {
        if (playerId == null || sourceId == null) {
            return Update.NONE;
        }
        Key key = new Key(playerId, sourceId);
        State previous = states.get(key);
        String normalizedSession = sessionId != null ? sessionId : "";
        boolean reset = previous == null || !previous.sessionId.equals(normalizedSession)
                || previous.sourcePos != sourcePos;
        Map<UUID, Endpoint> interested = interestedEndpoints(playerX, playerY, playerZ,
                endpoints != null ? endpoints : SpatialSnapshot.EMPTY);
        double sourceDiscoveryRange = Math.max(0, sourceRange) + DISCOVERY_LEAD_BLOCKS;
        boolean sourceInterested = distanceSquared(playerX, playerY, playerZ,
                unpackX(sourcePos) + 0.5D, unpackY(sourcePos) + 0.5D, unpackZ(sourcePos) + 0.5D)
                <= sourceDiscoveryRange * sourceDiscoveryRange;
        boolean subscribed = sourceInterested || !interested.isEmpty();
        if (!subscribed) {
            if (previous == null) {
                return Update.NONE;
            }
            long generation = nextGeneration(key);
            states.remove(key);
            return new Update(false, false, true, generation, List.of(), List.of());
        }

        Map<UUID, Endpoint> old = reset || previous == null ? Map.of() : previous.endpoints;
        List<Endpoint> upserts = new ArrayList<>();
        interested.forEach((endpointId, endpoint) -> {
            if (!endpoint.equals(old.get(endpointId))) {
                upserts.add(endpoint);
            }
        });
        List<UUID> removals = reset ? List.of() : old.keySet().stream()
                .filter(endpointId -> !interested.containsKey(endpointId)).toList();
        if (previous != null && !reset && upserts.isEmpty() && removals.isEmpty()) {
            return new Update(true, true, false, previous.generation, List.of(), List.of());
        }
        long generation = nextGeneration(key);
        states.put(key, new State(normalizedSession, sourcePos, Map.copyOf(interested), generation));
        return new Update(true, true, reset, generation, List.copyOf(upserts), List.copyOf(removals));
    }

    synchronized void forgetPlayer(UUID playerId) {
        if (playerId != null) {
            states.keySet().removeIf(key -> playerId.equals(key.playerId));
            generations.keySet().removeIf(key -> playerId.equals(key.playerId));
        }
    }

    synchronized void forgetSource(PlaybackSourceId sourceId) {
        if (sourceId != null) {
            states.keySet().removeIf(key -> sourceId.equals(key.sourceId));
        }
    }

    synchronized void clear() {
        states.clear();
        generations.clear();
    }

    private long nextGeneration(Key key) {
        long next = generations.getOrDefault(key, 0L) + 1L;
        generations.put(key, next);
        return next;
    }

    static SpatialSnapshot snapshot(List<Endpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return SpatialSnapshot.EMPTY;
        }
        Map<Cell, List<Endpoint>> mutable = new HashMap<>();
        for (Endpoint endpoint : endpoints) {
            mutable.computeIfAbsent(Cell.of(endpoint.endpointPos()), ignored -> new ArrayList<>()).add(endpoint);
        }
        Map<Cell, List<Endpoint>> cells = new HashMap<>();
        mutable.forEach((cell, entries) -> cells.put(cell, List.copyOf(entries)));
        return new SpatialSnapshot(Map.copyOf(cells));
    }

    private static Map<UUID, Endpoint> interestedEndpoints(double playerX, double playerY, double playerZ,
            SpatialSnapshot endpoints) {
        if (endpoints.cells.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Endpoint> result = new HashMap<>();
        for (var cell : endpoints.cells.entrySet()) {
            double maximumRadius = cell.getValue().stream().mapToDouble(
                    AudioEndpointSubscriptionTracker::discoveryRadius).max().orElse(0.0D);
            if (!cell.getKey().mayReach(playerX, playerY, playerZ, maximumRadius)) {
                continue;
            }
            for (Endpoint endpoint : cell.getValue()) {
                long pos = endpoint.endpointPos();
                double radius = discoveryRadius(endpoint);
                if (distanceSquared(playerX, playerY, playerZ,
                        unpackX(pos) + 0.5D, unpackY(pos) + 0.5D, unpackZ(pos) + 0.5D) <= radius * radius) {
                    result.put(endpoint.endpointId(), endpoint);
                }
            }
        }
        return result;
    }

    private static double discoveryRadius(Endpoint endpoint) {
        AudioPlaybackRange.Profile profile = AudioPlaybackRange.profile(
                endpoint.maxDistance(), endpoint.volume(), endpoint.volume());
        return profile.fadeEndDistance() + DISCOVERY_LEAD_BLOCKS;
    }

    private static double distanceSquared(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    private static int unpackY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    private static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    record Endpoint(UUID endpointId, long endpointPos, int channelIndex, float volume,
            boolean autoMixJoc, float maxDistance, long revision) {
    }

    record Update(boolean subscribed, boolean playbackRecipient, boolean reset, long generation,
            List<Endpoint> upserts, List<UUID> removals) {
        private static final Update NONE = new Update(false, false, false, 0L, List.of(), List.of());

        boolean packetRequired() {
            return reset || !upserts.isEmpty() || !removals.isEmpty();
        }
    }

    record SpatialSnapshot(Map<Cell, List<Endpoint>> cells) {
        private static final SpatialSnapshot EMPTY = new SpatialSnapshot(Map.of());
    }

    private record Key(UUID playerId, PlaybackSourceId sourceId) {
    }

    private record State(String sessionId, long sourcePos, Map<UUID, Endpoint> endpoints, long generation) {
    }

    private record Cell(int x, int y, int z) {
        static Cell of(long pos) {
            return new Cell(Math.floorDiv(unpackX(pos), CELL_SIZE_BLOCKS),
                    Math.floorDiv(unpackY(pos), CELL_SIZE_BLOCKS),
                    Math.floorDiv(unpackZ(pos), CELL_SIZE_BLOCKS));
        }

        boolean mayReach(double pointX, double pointY, double pointZ, double radius) {
            double minX = x * (double) CELL_SIZE_BLOCKS;
            double minY = y * (double) CELL_SIZE_BLOCKS;
            double minZ = z * (double) CELL_SIZE_BLOCKS;
            double maxX = minX + CELL_SIZE_BLOCKS;
            double maxY = minY + CELL_SIZE_BLOCKS;
            double maxZ = minZ + CELL_SIZE_BLOCKS;
            double closestX = Math.clamp(pointX, minX, maxX);
            double closestY = Math.clamp(pointY, minY, maxY);
            double closestZ = Math.clamp(pointZ, minZ, maxZ);
            return distanceSquared(pointX, pointY, pointZ, closestX, closestY, closestZ) <= radius * radius;
        }
    }
}
