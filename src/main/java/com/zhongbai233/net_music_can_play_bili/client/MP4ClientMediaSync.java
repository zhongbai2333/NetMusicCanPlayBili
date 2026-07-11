package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncHandler;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPolicy;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncPacket;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackTimelinePacket;

import java.util.UUID;

/**
 * MP4 packet-facing entry point backed by the shared client media sync handler.
 */
public final class MP4ClientMediaSync {
    private static final ClientMediaSyncPolicy SYNC_POLICY = new Mp4ClientMediaSyncPolicy();

    private MP4ClientMediaSync() {
    }

    public static void handleSync(MP4PlaybackSyncPacket payload) {
        ClientMediaSyncHandler.handleSync(payload, SYNC_POLICY);
    }

    public static void handleTimeline(MP4PlaybackTimelinePacket payload) {
        ClientMediaSyncHandler.handleTimeline(payload, SYNC_POLICY);
    }

    public static boolean hasStartedSound(UUID deviceId, String sessionId) {
        return ClientMediaPlaybackRegistry.hasAudioStarted(deviceId, sessionId);
    }
}