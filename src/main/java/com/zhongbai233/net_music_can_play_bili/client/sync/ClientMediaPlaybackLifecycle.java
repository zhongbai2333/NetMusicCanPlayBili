package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;

import java.util.UUID;

/** Shared lifecycle helpers for synchronized client media playback. */
public final class ClientMediaPlaybackLifecycle {
    private ClientMediaPlaybackLifecycle() {
    }

    public static void updateLyric(UUID deviceId, String sessionId, LyricRecord record, int lyricTick) {
        if (deviceId == null || record == null || !ClientMediaPlayback.isCurrent(deviceId, sessionId)) {
            return;
        }
        String current = currentLineAt(record.getLyrics(), lyricTick);
        String translated = currentLineAt(record.getTransLyrics(), lyricTick);
        ClientMediaPlaybackRegistry.computeIfPresent(deviceId,
                (ignored, active) -> active.withLyrics(record, current, translated));
    }

    public static void finish(UUID deviceId, String sessionId) {
        if (deviceId != null && sessionId != null && !sessionId.isBlank()) {
            ClientMediaPlaybackRegistry.finish(deviceId, sessionId);
            ClientMediaSoundRegistry.finish(deviceId, sessionId);
        }
    }

    private static String currentLineAt(it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap<String> lyrics, int tick) {
        if (lyrics == null || lyrics.isEmpty()) {
            return "";
        }
        int key = lyrics.firstIntKey();
        for (int candidate : lyrics.keySet().toIntArray()) {
            if (candidate > tick) {
                break;
            }
            key = candidate;
        }
        String line = lyrics.get(key);
        return line != null ? line : "";
    }
}