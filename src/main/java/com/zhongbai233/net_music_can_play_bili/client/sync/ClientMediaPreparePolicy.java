package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer;
import net.minecraft.client.resources.sounds.SoundInstance;

import java.net.URL;
import java.util.UUID;

/**
 * Carrier-specific policy for shared asynchronous client media prepare and
 * launch.
 */
public interface ClientMediaPreparePolicy {
    long prepareTimeoutSeconds();

    boolean canHear(UUID sourceId, boolean headphoneRouted);

    void stop(UUID sourceId);

    default boolean allowDolby(ClientMediaSyncPayload payload, UUID sourceId) {
        return true;
    }

    boolean shouldLoadLyrics(ClientMediaSyncPayload payload, UUID sourceId);

    default long startOffsetMillis(ClientMediaSyncPayload payload,
            ClientMediaPlaybackRegistry.ActivePlayback current) {
        return current != null ? current.elapsedMillis() : Math.max(0L, payload.elapsedMillis());
    }

    default long totalMillis(ClientMediaSyncPayload payload, ClientMediaPlaybackRegistry.ActivePlayback current) {
        long currentTotal = current != null ? current.durationMillis() : 0L;
        return currentTotal > 0L ? currentTotal : Math.max(0L, payload.durationSeconds()) * 1000L;
    }

    void loadLyricsAsync(UUID sourceId, String sessionId, String rawUrl, String songName);

    SoundInstance createSound(UUID sourceId, ClientMediaSyncPayload payload, URL url, LyricRecord lyricRecord,
            long startOffsetMillis);

    default void onPrepareDuplicate(ClientMediaSyncPayload payload, UUID sourceId) {
    }

    default void onPrepareScheduled(ClientMediaSyncPayload payload, UUID sourceId) {
    }

    default void onPrepareStarted(ClientMediaSyncPayload payload, UUID sourceId, boolean loadLyrics) {
    }

    default void onPrepareCompleted(ClientMediaSyncPayload payload, UUID sourceId,
            ClientMediaPreparer.PreparedMedia prepared, long costMillis) {
    }

    default void onPrepareFailed(ClientMediaSyncPayload payload, UUID sourceId, Throwable error) {
    }

    default void onPrepareTimeout(ClientMediaSyncPayload payload, UUID sourceId) {
    }

    default void onPrepareCancelledCannotHear(ClientMediaSyncPayload payload, UUID sourceId) {
    }

    default void onLaunch(ClientMediaSyncPayload payload, UUID sourceId, long startOffsetMillis, String playUrl) {
    }
}