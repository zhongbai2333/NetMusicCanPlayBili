package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared active sound registry for synchronized client media devices. */
public final class ClientMediaSoundRegistry {
    private static final Map<UUID, ClientMediaSoundHandle> ACTIVE_SOUNDS = new ConcurrentHashMap<>();

    private ClientMediaSoundRegistry() {
    }

    public static ClientMediaSoundHandle get(UUID sourceId) {
        return sourceId != null ? ACTIVE_SOUNDS.get(sourceId) : null;
    }

    public static void register(UUID sourceId, String sessionId, ClientMediaSoundHandle sound) {
        if (sourceId != null && ClientMediaPlaybackRegistry.isCurrent(sourceId, sessionId) && sound != null) {
            ACTIVE_SOUNDS.put(sourceId, sound);
        }
    }

    public static void remove(UUID sourceId) {
        if (sourceId != null) {
            ACTIVE_SOUNDS.remove(sourceId);
        }
    }

    public static void finish(UUID sourceId, String sessionId) {
        if (sourceId != null && sessionId != null && !sessionId.isBlank()) {
            ACTIVE_SOUNDS.computeIfPresent(sourceId,
                    (ignored, sound) -> sessionId.equals(sound.sessionId()) ? null : sound);
        }
    }

    public static void clear() {
        ACTIVE_SOUNDS.clear();
    }
}