package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer.AudioPresence;

import java.util.concurrent.ConcurrentHashMap;

/** 保存媒体 session 的权威音频能力；等待 readiness 不得清除这里的状态。 */
public final class VideoAudioPresenceRegistry {
    private final ConcurrentHashMap<String, AudioPresence> presenceBySession = new ConcurrentHashMap<>();

    public void publish(String sessionId, AudioPresence presence) {
        if (sessionId == null || sessionId.isBlank() || presence == null || presence == AudioPresence.UNKNOWN) {
            return;
        }
        presenceBySession.put(sessionId, presence);
    }

    public AudioPresence presence(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return AudioPresence.UNKNOWN;
        }
        return presenceBySession.getOrDefault(sessionId, AudioPresence.UNKNOWN);
    }

    public void forget(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            presenceBySession.remove(sessionId);
        }
    }

    public void clear() {
        presenceBySession.clear();
    }
}