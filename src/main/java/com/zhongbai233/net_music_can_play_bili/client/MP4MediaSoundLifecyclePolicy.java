package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundHandle;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundLifecyclePolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaRetryHandler;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundRegistry;

import java.util.UUID;

/** MP4 compatibility policy for synchronized media sound lifecycle events. */
final class MP4MediaSoundLifecyclePolicy implements ClientMediaSoundLifecyclePolicy {
    static final MP4MediaSoundLifecyclePolicy INSTANCE = new MP4MediaSoundLifecyclePolicy();

    private MP4MediaSoundLifecyclePolicy() {
    }

    @Override
    public void registerSound(UUID deviceId, String sessionId, ClientMediaSoundHandle sound) {
        ClientMediaSoundRegistry.register(deviceId, sessionId, sound);
    }

    @Override
    public boolean recoverAfterStreamFailure(UUID deviceId, String sessionId, Throwable error) {
        return ClientMediaRetryHandler.retryAfterStreamFailure(deviceId, sessionId, error,
                Mp4ClientMediaRetryPolicy.INSTANCE);
    }

    @Override
    public void onCompleted(UUID deviceId, String sessionId) {
        MP4QueueCompletionPolicy.INSTANCE.onCompleted(deviceId, sessionId);
    }

    @Override
    public void finish(UUID deviceId, String sessionId) {
        ClientMediaPlaybackRegistry.finishSession(deviceId, sessionId);
    }
}