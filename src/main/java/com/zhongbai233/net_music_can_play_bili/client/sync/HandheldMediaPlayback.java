package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.Optional;

/** 手持媒体播放状态的只读描述，供 GUI、字幕和视频帧源共用。 */
public record HandheldMediaPlayback(Optional<PlaybackSessionId> playbackSessionId, String rawUrl, String title,
        MediaTimelineClock.TimelineSnapshot timeline, boolean allowAiSubtitle) {
    public static final HandheldMediaPlayback EMPTY = new HandheldMediaPlayback(Optional.empty(), "", "",
            MediaTimelineClock.TimelineSnapshot.EMPTY, false);

    public HandheldMediaPlayback {
        playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
    }

    public HandheldMediaPlayback(String sessionId, String rawUrl, String title,
            MediaTimelineClock.TimelineSnapshot timeline, boolean allowAiSubtitle) {
        this(PlaybackSessionId.parse(sessionId), rawUrl, title, timeline, allowAiSubtitle);
    }

    public String sessionId() {
        return playbackSessionId.map(session -> session.value()).orElse("");
    }

    public boolean hasSession() {
        return playbackSessionId.isPresent();
    }

    public boolean hasPlayableVideoSource() {
        return hasSession()
                && rawUrl != null && !rawUrl.isBlank()
                && timeline != null && timeline.totalMillis() > 0L;
    }
}
