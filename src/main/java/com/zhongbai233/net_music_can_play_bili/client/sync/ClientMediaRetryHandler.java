package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Shared stream retry coordinator for synchronized client media playback. */
public final class ClientMediaRetryHandler {
    private static final ClientMediaRetryRegistry PENDING_RETRIES = new ClientMediaRetryRegistry();

    private ClientMediaRetryHandler() {
    }

    public static boolean retryAfterStreamFailure(UUID deviceId, String sessionId, Throwable error,
            ClientMediaRetryPolicy policy) {
        PlaybackSessionId playbackSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        return playbackSessionId != null
                && retryAfterStreamFailure(deviceId, playbackSessionId, error, policy);
    }

    public static boolean retryAfterStreamFailure(UUID deviceId, PlaybackSessionId sessionId, Throwable error,
            ClientMediaRetryPolicy policy) {
        if (deviceId == null || sessionId == null || policy == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
        if (active == null || !active.playbackSessionId().filter(sessionId::equals).isPresent()) {
            return false;
        }
        if (!markPending(deviceId, sessionId)) {
            return false;
        }
        ClientMediaPlaybackRegistry.ActivePlayback admitted = ClientMediaPlaybackRegistry.get(deviceId);
        if (admitted == null || !admitted.playbackSessionId().filter(sessionId::equals).isPresent()) {
            PENDING_RETRIES.forget(PlaybackSourceId.of(deviceId), sessionId);
            return false;
        }
        policy.onRetryScheduled(deviceId, sessionId.value(), admitted, error);
        CompletableFuture.delayedExecutor(Math.max(0L, policy.retryDelayMillis()), TimeUnit.MILLISECONDS)
                .execute(() -> Minecraft.getInstance().execute(() -> {
                    PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
                    PENDING_RETRIES.dispatchIfPending(sourceId, sessionId, () -> {
                        ClientMediaPlaybackRegistry.ActivePlayback current = ClientMediaPlaybackRegistry.get(deviceId);
                        if (current == null || !current.playbackSessionId().filter(sessionId::equals).isPresent()) {
                            PENDING_RETRIES.forget(sourceId, sessionId);
                            return false;
                        }
                        return ClientMediaRetryDispatch.dispatch(policy, deviceId, sessionId, current, error, () -> {
                            if (PENDING_RETRIES.forget(sourceId, sessionId)) {
                                ClientMediaPlaybackRegistry.finishSession(deviceId, sessionId);
                            }
                        });
                    });
                }));
        return true;
    }

    public static boolean isPending(UUID deviceId, String sessionId) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        return parsedSessionId != null && isPending(deviceId, parsedSessionId);
    }

    public static boolean isPending(UUID deviceId, PlaybackSessionId sessionId) {
        return deviceId != null && sessionId != null
                && PENDING_RETRIES.contains(PlaybackSourceId.of(deviceId), sessionId);
    }

    static void onSessionAccepted(UUID deviceId, PlaybackSessionId sessionId) {
        if (deviceId != null && sessionId != null) {
            PENDING_RETRIES.forgetSource(PlaybackSourceId.of(deviceId));
        }
    }

    /** Confirms only the retry that owns this exact retained logical session. */
    static boolean onSessionRefreshed(UUID deviceId, PlaybackSessionId sessionId) {
        return deviceId != null && sessionId != null
                && PENDING_RETRIES.forget(PlaybackSourceId.of(deviceId), sessionId);
    }

    static void removePendingForDevice(UUID deviceId) {
        if (deviceId != null) {
            PENDING_RETRIES.forgetSource(PlaybackSourceId.of(deviceId));
        }
    }

    static void clearPending() {
        PENDING_RETRIES.clear();
    }

    private static boolean markPending(UUID deviceId, PlaybackSessionId sessionId) {
        return sessionId != null && PENDING_RETRIES.tryMark(PlaybackSourceId.of(deviceId), sessionId);
    }
}
