package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;

/** Carrier-specific retry action for synchronized media stream failures. */
public interface ClientMediaRetryPolicy {
    long retryDelayMillis();

    void scheduleRetry(UUID deviceId, String sessionId, ClientMediaPlaybackRegistry.ActivePlayback active,
            Throwable error);

    default void onRetryScheduled(UUID deviceId, String sessionId, ClientMediaPlaybackRegistry.ActivePlayback active,
            Throwable error) {
    }
}