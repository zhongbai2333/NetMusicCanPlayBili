package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackLifecycle;

import java.util.UUID;

/**
 * Pad completion behavior: finish the active synchronized media session without
 * MP4 queue control.
 */
final class PadMediaCompletionPolicy {
    static final PadMediaCompletionPolicy INSTANCE = new PadMediaCompletionPolicy();

    private PadMediaCompletionPolicy() {
    }

    public void onCompleted(UUID deviceId, String sessionId) {
        ClientMediaPlaybackLifecycle.finish(deviceId, sessionId);
    }
}