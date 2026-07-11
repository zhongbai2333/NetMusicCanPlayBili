package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;

/**
 * Carrier-specific lifecycle callbacks used by shared synchronized media sound
 * instances.
 */
public interface ClientMediaSoundLifecyclePolicy {
    void registerSound(UUID deviceId, String sessionId, ClientMediaSoundHandle sound);

    boolean recoverAfterStreamFailure(UUID deviceId, String sessionId, Throwable error);

    void onCompleted(UUID deviceId, String sessionId);

    void finish(UUID deviceId, String sessionId);
}