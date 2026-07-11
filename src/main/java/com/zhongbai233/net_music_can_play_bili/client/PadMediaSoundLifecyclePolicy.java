package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackLifecycle;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaRetryHandler;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundHandle;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundLifecyclePolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundRegistry;

import java.util.UUID;

/** Pad policy for synchronized media sound lifecycle events. */
final class PadMediaSoundLifecyclePolicy implements ClientMediaSoundLifecyclePolicy {
    static final PadMediaSoundLifecyclePolicy INSTANCE = new PadMediaSoundLifecyclePolicy();

    private PadMediaSoundLifecyclePolicy() {
    }

    @Override
    public void registerSound(UUID deviceId, String sessionId, ClientMediaSoundHandle sound) {
        ClientMediaSoundRegistry.register(deviceId, sessionId, sound);
    }

    @Override
    public boolean recoverAfterStreamFailure(UUID deviceId, String sessionId, Throwable error) {
        return ClientMediaRetryHandler.retryAfterStreamFailure(deviceId, sessionId, error,
                PadClientMediaRetryPolicy.INSTANCE);
    }

    @Override
    public void onCompleted(UUID deviceId, String sessionId) {
        PadMediaCompletionPolicy.INSTANCE.onCompleted(deviceId, sessionId);
    }

    @Override
    public void finish(UUID deviceId, String sessionId) {
        ClientMediaPlaybackLifecycle.finish(deviceId, sessionId);
    }
}