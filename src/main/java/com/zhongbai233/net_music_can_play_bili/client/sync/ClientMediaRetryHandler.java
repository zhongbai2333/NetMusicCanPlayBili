package com.zhongbai233.net_music_can_play_bili.client.sync;

import net.minecraft.client.Minecraft;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Shared stream retry coordinator for synchronized client media playback. */
public final class ClientMediaRetryHandler {
    private static final Set<String> PENDING_RETRY_SESSIONS = ConcurrentHashMap.newKeySet();

    private ClientMediaRetryHandler() {
    }

    public static boolean retryAfterStreamFailure(UUID deviceId, String sessionId, Throwable error,
            ClientMediaRetryPolicy policy) {
        if (deviceId == null || sessionId == null || sessionId.isBlank() || policy == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
        if (active == null || !sessionId.equals(active.sessionId())) {
            return false;
        }
        if (!markPending(deviceId, sessionId)) {
            return false;
        }
        policy.onRetryScheduled(deviceId, sessionId, active, error);
        CompletableFuture.delayedExecutor(Math.max(0L, policy.retryDelayMillis()), TimeUnit.MILLISECONDS)
                .execute(() -> Minecraft.getInstance().execute(() -> {
                    ClientMediaPlaybackRegistry.ActivePlayback current = ClientMediaPlaybackRegistry.get(deviceId);
                    if (current == null || !sessionId.equals(current.sessionId())) {
                        return;
                    }
                    policy.scheduleRetry(deviceId, sessionId, current, error);
                }));
        return true;
    }

    public static boolean isPending(UUID deviceId, String sessionId) {
        return deviceId != null && sessionId != null && PENDING_RETRY_SESSIONS.contains(retryKey(deviceId, sessionId));
    }

    static void removePendingForDevice(UUID deviceId) {
        if (deviceId != null) {
            PENDING_RETRY_SESSIONS.removeIf(key -> key.startsWith(deviceId + ":"));
        }
    }

    static void clearPending() {
        PENDING_RETRY_SESSIONS.clear();
    }

    private static boolean markPending(UUID deviceId, String sessionId) {
        return PENDING_RETRY_SESSIONS.add(retryKey(deviceId, sessionId));
    }

    private static String retryKey(UUID deviceId, String sessionId) {
        return deviceId + ":" + sessionId;
    }
}