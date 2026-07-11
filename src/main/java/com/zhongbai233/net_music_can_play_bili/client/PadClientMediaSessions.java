package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackSessions;

import java.util.UUID;

/** Pad carrier hooks for shared client media playback session cleanup. */
public final class PadClientMediaSessions {
    private PadClientMediaSessions() {
    }

    public static void stop(UUID deviceId) {
        ClientMediaPlaybackSessions.stop(deviceId, PadClientMediaSessions::stopVideo);
    }

    private static void stopVideo(UUID deviceId) {
        MP4HandheldVideoClient.stop(deviceId, "Pad 播放已停止");
    }
}