package com.zhongbai233.net_music_can_play_bili.client.sync;

import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Shared stream retry coordinator for synchronized client media playback. */
public final class ClientMediaRetryHandler {
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
        if (!ClientMediaStreamRecovery.markPending(deviceId, sessionId)) {
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
}