package com.zhongbai233.net_music_can_play_bili.client.sync;

/** 手持媒体播放状态的只读描述，供 GUI、字幕和视频帧源共用。 */
public record HandheldMediaPlayback(String sessionId, String rawUrl, String title,
        MediaTimelineClock.TimelineSnapshot timeline, boolean allowAiSubtitle) {
    public static final HandheldMediaPlayback EMPTY = new HandheldMediaPlayback("", "", "",
            MediaTimelineClock.TimelineSnapshot.EMPTY, false);

    public boolean hasSession() {
        return sessionId != null && !sessionId.isBlank();
    }

    public boolean hasPlayableVideoSource() {
        return hasSession()
                && rawUrl != null && !rawUrl.isBlank()
                && timeline != null && timeline.totalMillis() > 0L;
    }
}
