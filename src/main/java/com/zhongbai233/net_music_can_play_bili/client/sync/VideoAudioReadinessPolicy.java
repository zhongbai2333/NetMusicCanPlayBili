package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer.AudioPresence;

/** 纯视频与音频锚定视频的 readiness 决策，不依赖 Minecraft runtime。 */
public final class VideoAudioReadinessPolicy {
    private VideoAudioReadinessPolicy() {
    }

    public static boolean allowsVideo(AudioPresence presence, boolean audioReady) {
        if (presence == AudioPresence.ABSENT) {
            return true;
        }
        return presence == AudioPresence.PRESENT && audioReady;
    }
}