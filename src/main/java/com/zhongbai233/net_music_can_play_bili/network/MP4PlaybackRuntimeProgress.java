package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.Optional;

/** Immutable runtime MP4 progress, separate from the SavedData codec representation. */
record MP4PlaybackRuntimeProgress(int queueIndex, long elapsedMillis, int durationSeconds, int volumePerMille,
        Optional<PlaybackSessionId> playbackSessionId, boolean playing) {
    MP4PlaybackRuntimeProgress {
        queueIndex = Math.max(0, queueIndex);
        durationSeconds = Math.max(0, durationSeconds);
        long maxElapsed = durationSeconds > 0
                ? Math.max(0L, durationSeconds * 1000L - 50L)
                : Long.MAX_VALUE;
        elapsedMillis = Math.max(0L, Math.min(maxElapsed, elapsedMillis));
        volumePerMille = Math.max(0, Math.min(1000, volumePerMille));
        playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
    }

    MP4PlaybackRuntimeProgress(int queueIndex, long elapsedMillis, int durationSeconds, int volumePerMille,
            String sessionId, boolean playing) {
        this(queueIndex, elapsedMillis, durationSeconds, volumePerMille, PlaybackSessionId.parse(sessionId), playing);
    }

    String sessionId() {
        return playbackSessionId.map(PlaybackSessionId::value).orElse("");
    }
}
