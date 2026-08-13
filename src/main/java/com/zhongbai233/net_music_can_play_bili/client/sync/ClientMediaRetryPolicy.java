package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;

/** Carrier-specific retry action for synchronized media stream failures. */
public interface ClientMediaRetryPolicy {
    long retryDelayMillis();

    void scheduleRetry(UUID deviceId, String sessionId, ClientMediaPlaybackRegistry.ActivePlayback active,
            Throwable error);

    /**
     * Admission-aware retry dispatch. Existing policies keep their legacy
     * behavior; policies that can reject a command before sending should return
     * that exact result.
     */
    default boolean tryScheduleRetry(UUID deviceId, String sessionId,
            ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
        scheduleRetry(deviceId, sessionId, active, error);
        return true;
    }

    default void onRetryScheduled(UUID deviceId, String sessionId, ClientMediaPlaybackRegistry.ActivePlayback active,
            Throwable error) {
    }
}
