package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.Optional;

record HandheldPlaybackKey(Optional<PlaybackSessionId> playbackSessionId, String rawUrl, int quality,
        boolean allowAiSubtitle, boolean rgbaFallback) {
    static final HandheldPlaybackKey EMPTY = new HandheldPlaybackKey(Optional.empty(), "", 0, false, false);

    HandheldPlaybackKey {
        playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
    }

    String sessionId() {
        return playbackSessionId.isEmpty() ? "" : playbackSessionId.get().value();
    }
}
