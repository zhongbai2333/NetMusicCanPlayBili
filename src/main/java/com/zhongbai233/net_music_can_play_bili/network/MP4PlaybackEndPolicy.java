package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackEndReason;

/** Admission shared by the real server terminal-packet handler. */
final class MP4PlaybackEndPolicy {
    enum Decision { IGNORE, ADVANCE, STOP }
    static Decision decide(boolean owner, boolean exactSession, boolean exactTransport, boolean activeDevice,
            boolean pendingControl, int durationSeconds, PlaybackEndReason reason) {
        if (!owner || !exactSession || !exactTransport || !activeDevice || pendingControl || (durationSeconds > 0 && reason != PlaybackEndReason.FAILED)
                || reason == null || reason == PlaybackEndReason.PAUSED || reason == PlaybackEndReason.CANCELLED) {
            return Decision.IGNORE;
        }
        return reason == PlaybackEndReason.NATURAL_EOF ? Decision.ADVANCE : Decision.STOP;
    }
}
