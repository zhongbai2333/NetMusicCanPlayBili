package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;

/** “正在播放”提示在实际可听边界外保留一小段预警距离。 */
public final class PlaybackNoticePolicy {
    private PlaybackNoticePolicy() {
    }

    public static boolean isWithinNoticeRange(float distance, float volume) {
        return AudioPlaybackRange.evaluateSphere(distance, AudioPlaybackRange.DEFAULT_DISTANCE,
                volume, false).noticeActive();
    }
}
