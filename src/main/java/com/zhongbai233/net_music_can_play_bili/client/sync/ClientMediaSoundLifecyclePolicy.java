package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;

/**
 * Carrier-specific lifecycle callbacks used by shared synchronized media sound
 * instances.
 */
public interface ClientMediaSoundLifecyclePolicy {
    void registerSound(UUID deviceId, String sessionId, ClientMediaSoundHandle sound);

    /**
     * Admission-aware registration used by asynchronously constructed sounds.
     * Existing policies retain their legacy behavior unless they override this
     * method with an exact registry result.
     */
    default boolean tryRegisterSound(UUID deviceId, String sessionId, ClientMediaSoundHandle sound) {
        registerSound(deviceId, sessionId, sound);
        return true;
    }

    boolean recoverAfterStreamFailure(UUID deviceId, String sessionId, Throwable error);

    void onCompleted(UUID deviceId, String sessionId);

    void finish(UUID deviceId, String sessionId);
}
