package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import java.util.UUID;

/** Server timelines own queue advancement; a sound ending may only retire its exact local session. */
final class MP4QueueCompletionPolicy {
    static final MP4QueueCompletionPolicy INSTANCE = new MP4QueueCompletionPolicy();
    private MP4QueueCompletionPolicy() { }

    public void onCompleted(UUID deviceId, String sessionId) {
        // A callback from a replaced decoder must not revoke a newer session's
        // restoration epoch. Queue advancement remains exclusively server-owned.
        var active = ClientMediaPlaybackRegistry.get(deviceId);
        if (active != null && active.playbackSessionId().filter(session -> session.value().equals(sessionId)).isPresent()) {
            MP4AutoResumeClient.consumeRecovery(deviceId);
        }
        ClientMediaPlaybackRegistry.finishSession(deviceId, sessionId);
    }
}
