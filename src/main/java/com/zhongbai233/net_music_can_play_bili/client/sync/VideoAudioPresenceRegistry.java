package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer.AudioPresence;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.concurrent.ConcurrentHashMap;

/** 保存媒体 session 的权威音频能力；等待 readiness 不得清除这里的状态。 */
public final class VideoAudioPresenceRegistry {
    private final ConcurrentHashMap<PlaybackSessionId, AudioPresence> presenceBySession = new ConcurrentHashMap<>();

    public void publish(String sessionId, AudioPresence presence) {
        PlaybackSessionId.parse(sessionId).ifPresent(parsedSessionId -> publish(parsedSessionId, presence));
    }

    public void publish(PlaybackSessionId sessionId, AudioPresence presence) {
        if (sessionId == null || presence == null || presence == AudioPresence.UNKNOWN) {
            return;
        }
        presenceBySession.put(sessionId, presence);
    }

    public AudioPresence presence(String sessionId) {
        return PlaybackSessionId.parse(sessionId)
                .map(this::presence)
                .orElse(AudioPresence.UNKNOWN);
    }

    public AudioPresence presence(PlaybackSessionId sessionId) {
        return sessionId != null
                ? presenceBySession.getOrDefault(sessionId, AudioPresence.UNKNOWN)
                : AudioPresence.UNKNOWN;
    }

    public void forget(String sessionId) {
        PlaybackSessionId.parse(sessionId).ifPresent(this::forget);
    }

    public void forget(PlaybackSessionId sessionId) {
        if (sessionId != null) {
            presenceBySession.remove(sessionId);
        }
    }

    public void clear() {
        presenceBySession.clear();
    }
}
