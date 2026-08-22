package com.zhongbai233.net_music_can_play_bili.client.audio;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Lightweight owner-volume state shared with playback lifecycle code without loading Minecraft/OpenAL. */
public final class ClientAudioOwnerVolumes {
    private static final ConcurrentMap<UUID, Float> VOLUMES = new ConcurrentHashMap<>();

    private ClientAudioOwnerVolumes() {
    }

    public static float getOrDefault(UUID ownerId, float fallback) {
        return ownerId != null ? VOLUMES.getOrDefault(ownerId, fallback) : fallback;
    }

    public static void put(UUID ownerId, float volume) {
        if (ownerId != null) {
            VOLUMES.put(ownerId, volume);
        }
    }

    public static void remove(UUID ownerId) {
        if (ownerId != null) {
            VOLUMES.remove(ownerId);
        }
    }

    public static void clear() {
        VOLUMES.clear();
    }
}
