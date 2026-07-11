package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared pending-retry state for synchronized client media streams. */
public final class ClientMediaStreamRecovery {
    private static final Set<String> PENDING_RETRY_SESSIONS = ConcurrentHashMap.newKeySet();

    private ClientMediaStreamRecovery() {
    }

    public static boolean markPending(UUID deviceId, String sessionId) {
        if (deviceId == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return PENDING_RETRY_SESSIONS.add(key(deviceId, sessionId));
    }

    public static boolean isPending(UUID deviceId, String sessionId) {
        return deviceId != null && sessionId != null && PENDING_RETRY_SESSIONS.contains(key(deviceId, sessionId));
    }

    public static void removeForDevice(UUID deviceId) {
        if (deviceId != null) {
            PENDING_RETRY_SESSIONS.removeIf(key -> key.startsWith(deviceId + ":"));
        }
    }

    public static void clear() {
        PENDING_RETRY_SESSIONS.clear();
    }

    private static String key(UUID deviceId, String sessionId) {
        return deviceId + ":" + sessionId;
    }
}