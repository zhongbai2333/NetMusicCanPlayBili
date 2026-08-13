package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** UUID-compatible facade over the strongly typed MP4 playback source session registry. */
final class MP4PlaybackSourceSessionRegistry<S> {
    private final MP4PlaybackSessionRegistry<PlaybackSourceId, S> sessions = new MP4PlaybackSessionRegistry<>();

    S get(UUID sourceId) {
        return sourceId != null ? sessions.get(PlaybackSourceId.of(sourceId)) : null;
    }

    boolean contains(UUID sourceId) {
        return sourceId != null && sessions.contains(PlaybackSourceId.of(sourceId));
    }

    S replace(UUID sourceId, S session) {
        return sessions.replace(PlaybackSourceId.of(sourceId), session);
    }

    boolean replace(UUID sourceId, S expected, S replacement) {
        return sourceId != null
                && sessions.replace(PlaybackSourceId.of(sourceId), expected, replacement);
    }

    S updateIfPresent(UUID sourceId, UnaryOperator<S> updater) {
        return sourceId != null
                ? sessions.updateIfPresent(PlaybackSourceId.of(sourceId), updater)
                : null;
    }

    S remove(UUID sourceId) {
        return sourceId != null ? sessions.remove(PlaybackSourceId.of(sourceId)) : null;
    }

    boolean remove(UUID sourceId, S expected) {
        return sourceId != null && sessions.remove(PlaybackSourceId.of(sourceId), expected);
    }

    Long markMissingIfCurrent(UUID sourceId, S expected, long gameTime) {
        return sourceId != null
                ? sessions.markMissingIfCurrent(PlaybackSourceId.of(sourceId), expected, gameTime)
                : null;
    }

    boolean clearMissingIfCurrent(UUID sourceId, S expected) {
        return sourceId != null
                && sessions.clearMissingIfCurrent(PlaybackSourceId.of(sourceId), expected);
    }

    List<Map.Entry<UUID, S>> entries() {
        return sessions.entries().stream()
                .map(entry -> Map.entry(entry.getKey().value(), entry.getValue()))
                .toList();
    }

    List<S> values() {
        return sessions.values();
    }

    boolean isEmpty() {
        return sessions.isEmpty();
    }

    int size() {
        return sessions.size();
    }

    void clear() {
        sessions.clear();
    }
}
