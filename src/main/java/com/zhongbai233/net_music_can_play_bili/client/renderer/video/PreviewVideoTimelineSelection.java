package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.Optional;

/** Pure admission rule for advancing preview video before an OpenAL stream is available. */
final class PreviewVideoTimelineSelection {
    private PreviewVideoTimelineSelection() {
    }

    static boolean useRegistryTimeline(PlaybackSessionId expectedSession, boolean audioStarted,
            Optional<PlaybackSessionId> snapshotSession, long snapshotMediaMillis) {
        if (expectedSession == null || audioStarted || snapshotMediaMillis < 0L) {
            return false;
        }
        Optional<PlaybackSessionId> normalized = snapshotSession != null ? snapshotSession : Optional.empty();
        return normalized.filter(expectedSession::equals).isPresent();
    }
}
