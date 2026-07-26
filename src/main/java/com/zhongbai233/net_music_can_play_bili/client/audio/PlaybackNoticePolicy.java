package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;

/** “正在播放”提示在实际可听边界外保留一小段预警距离。 */
public final class PlaybackNoticePolicy {
    private static final float NOTICE_LEAD_FRACTION = 0.10F;

    private PlaybackNoticePolicy() {
    }

    public static boolean isWithinNoticeRange(float distance, float volume) {
        float clampedVolume = AudioUtils.clampGain(volume);
        if (clampedVolume <= 0.0F) {
            return false;
        }
        float audibleDistance = AudioUtils.MAX_AUDIBLE_DISTANCE * clampedVolume;
        float fadeEnd = audibleDistance * (1.0F + AudioUtils.AUDIBLE_FADE_FRACTION);
        float noticeEnd = fadeEnd + audibleDistance * NOTICE_LEAD_FRACTION;
        return distance < noticeEnd;
    }
}