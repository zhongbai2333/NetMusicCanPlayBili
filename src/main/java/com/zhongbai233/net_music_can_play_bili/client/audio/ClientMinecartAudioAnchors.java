package com.zhongbai233.net_music_can_play_bili.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves moving MinecartRevolution playback sources by session. */
public final class ClientMinecartAudioAnchors {
    private static final ConcurrentHashMap<String, Anchor> ANCHORS = new ConcurrentHashMap<>();

    private ClientMinecartAudioAnchors() {
    }

    public static void register(String sessionId, int entityId, UUID entityUuid) {
        if (sessionId == null || sessionId.isBlank() || entityId < 0 || entityUuid == null) {
            return;
        }
        ANCHORS.put(sessionId, new Anchor(entityId, entityUuid, null));
    }

    public static boolean isMoving(String sessionId) {
        return sessionId != null && ANCHORS.containsKey(sessionId);
    }

    public static UUID entityUuid(String sessionId) {
        Anchor anchor = sessionId != null ? ANCHORS.get(sessionId) : null;
        return anchor != null ? anchor.entityUuid() : null;
    }

    public static Entity entity(String sessionId) {
        Anchor anchor = sessionId != null ? ANCHORS.get(sessionId) : null;
        Minecraft minecraft = Minecraft.getInstance();
        if (anchor == null || minecraft.level == null) {
            return null;
        }
        Entity entity = minecraft.level.getEntity(anchor.entityId());
        if (entity == null || entity.isRemoved() || !anchor.entityUuid().equals(entity.getUUID())) {
            return null;
        }
        return entity;
    }

    public static Vec3 currentPosition(String sessionId) {
        Entity entity = entity(sessionId);
        if (entity == null) {
            return null;
        }
        Vec3 position = entity.position().add(0.0D, 0.5D, 0.0D);
        ANCHORS.computeIfPresent(sessionId,
                (ignored, anchor) -> new Anchor(anchor.entityId(), anchor.entityUuid(), position));
        return position;
    }

    public static Vec3 position(String sessionId) {
        Vec3 current = currentPosition(sessionId);
        if (current != null) {
            return current;
        }
        Anchor anchor = sessionId != null ? ANCHORS.get(sessionId) : null;
        return anchor != null ? anchor.lastPosition() : null;
    }

    public static void forget(String sessionId) {
        if (sessionId != null) {
            ANCHORS.remove(sessionId);
        }
    }

    public static void clear() {
        ANCHORS.clear();
    }

    private record Anchor(int entityId, UUID entityUuid, Vec3 lastPosition) {
    }
}