package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackSessions;

import java.util.UUID;

/** MP4 carrier hooks for shared client media playback session cleanup. */
public final class MP4ClientMediaSessions {
    private MP4ClientMediaSessions() {
    }

    public static void stop(UUID deviceId) {
        ClientMediaPlaybackSessions.stop(deviceId, MP4ClientMediaSessions::stopVideo);
    }

    public static void clearAll() {
        ClientMediaPlaybackSessions.clearAll(MP4ClientMediaSessions::clearVideos);
    }

    private static void stopVideo(UUID deviceId) {
        MP4HandheldVideoClient.stop(deviceId, "播放已停止");
    }

    private static void clearVideos() {
        MP4HandheldVideoClient.clearAll();
    }
}