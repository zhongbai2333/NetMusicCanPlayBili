package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

/** Pure exact-session admission policy for server-authoritative turntable stops. */
public final class ModernTurntableStopPolicy {
    private ModernTurntableStopPolicy() {
    }

    public static Decision decide(String stoppedSessionId, String activeSessionId) {
        PlaybackSessionId stopped = PlaybackSessionId.parse(stoppedSessionId).orElse(null);
        PlaybackSessionId active = PlaybackSessionId.parse(activeSessionId).orElse(null);
        if (stopped == null || active == null) {
            return Decision.IGNORE_INVALID;
        }
        return stopped.equals(active) ? Decision.STOP_EXACT : Decision.IGNORE_STALE;
    }

    public enum Decision {
        STOP_EXACT,
        IGNORE_STALE,
        IGNORE_INVALID
    }
}
