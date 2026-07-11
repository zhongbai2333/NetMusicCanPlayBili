package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared de-duplication state for asynchronous client media prepare jobs. */
public final class ClientMediaPrepareTracker {
    private static final Set<String> PREPARES = ConcurrentHashMap.newKeySet();

    private ClientMediaPrepareTracker() {
    }

    public static boolean markScheduled(UUID deviceId, String sessionId, boolean headphoneRouted) {
        return PREPARES.add(key(deviceId, sessionId, headphoneRouted));
    }

    public static void complete(UUID deviceId, String sessionId, boolean headphoneRouted) {
        PREPARES.remove(key(deviceId, sessionId, headphoneRouted));
    }

    public static void removeForDevice(UUID deviceId) {
        if (deviceId != null) {
            PREPARES.removeIf(key -> key.startsWith(deviceId + ":"));
        }
    }

    public static void clear() {
        PREPARES.clear();
    }

    private static String key(UUID deviceId, String sessionId, boolean headphoneRouted) {
        return String.valueOf(deviceId) + ':' + sessionId + ':' + headphoneRouted;
    }
}