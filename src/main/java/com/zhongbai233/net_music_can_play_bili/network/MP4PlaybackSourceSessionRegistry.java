package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/** Owns active playback sessions and the missing-source grace period for each exact session. */
final class MP4PlaybackSourceSessionRegistry<S> {
    private final ConcurrentMap<PlaybackSourceId, S> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<PlaybackSourceId, Long> missingSince = new ConcurrentHashMap<>();

    S get(UUID sourceId) {
        return sourceId != null ? sessions.get(PlaybackSourceId.of(sourceId)) : null;
    }

    boolean contains(UUID sourceId) {
        return sourceId != null && sessions.containsKey(PlaybackSourceId.of(sourceId));
    }

    S replace(UUID sourceId, S session) {
        PlaybackSourceId key = PlaybackSourceId.of(sourceId);
        S previous = sessions.put(key, Objects.requireNonNull(session, "session"));
        missingSince.remove(key);
        return previous;
    }

    boolean replace(UUID sourceId, S expected, S replacement) {
        if (sourceId == null || expected == null || replacement == null) {
            return false;
        }
        PlaybackSourceId key = PlaybackSourceId.of(sourceId);
        AtomicBoolean replaced = new AtomicBoolean();
        sessions.computeIfPresent(key, (ignored, current) -> {
            // Equal session values can still belong to different playback admissions.
            if (current != expected) {
                return current;
            }
            replaced.set(true);
            return replacement;
        });
        if (!replaced.get()) {
            return false;
        }
        missingSince.remove(key);
        return true;
    }

    S updateIfPresent(UUID sourceId, UnaryOperator<S> updater) {
        if (sourceId == null) {
            return null;
        }
        Objects.requireNonNull(updater, "updater");
        return sessions.computeIfPresent(PlaybackSourceId.of(sourceId),
                (ignored, current) -> Objects.requireNonNull(updater.apply(current), "updated session"));
    }

    S remove(UUID sourceId) {
        if (sourceId == null) {
            return null;
        }
        PlaybackSourceId key = PlaybackSourceId.of(sourceId);
        S removed = sessions.remove(key);
        missingSince.remove(key);
        return removed;
    }

    boolean remove(UUID sourceId, S expected) {
        if (sourceId == null || expected == null) {
            return false;
        }
        PlaybackSourceId key = PlaybackSourceId.of(sourceId);
        AtomicBoolean removed = new AtomicBoolean();
        sessions.computeIfPresent(key, (ignored, current) -> {
            if (current != expected) {
                return current;
            }
            removed.set(true);
            return null;
        });
        if (!removed.get()) {
            return false;
        }
        missingSince.remove(key);
        return true;
    }

    Long markMissingIfCurrent(UUID sourceId, S expected, long gameTime) {
        if (sourceId == null || expected == null) {
            return null;
        }
        PlaybackSourceId key = PlaybackSourceId.of(sourceId);
        AtomicReference<Long> result = new AtomicReference<>();
        sessions.computeIfPresent(key, (ignored, current) -> {
            if (current == expected) {
                result.set(missingSince.computeIfAbsent(key, missing -> gameTime));
            }
            return current;
        });
        return result.get();
    }

    boolean clearMissingIfCurrent(UUID sourceId, S expected) {
        if (sourceId == null || expected == null) {
            return false;
        }
        PlaybackSourceId key = PlaybackSourceId.of(sourceId);
        AtomicBoolean currentSession = new AtomicBoolean();
        sessions.computeIfPresent(key, (ignored, current) -> {
            if (current == expected) {
                missingSince.remove(key);
                currentSession.set(true);
            }
            return current;
        });
        return currentSession.get();
    }

    List<Map.Entry<UUID, S>> entries() {
        return sessions.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey().value(), entry.getValue()))
                .toList();
    }

    List<S> values() {
        return List.copyOf(sessions.values());
    }

    boolean isEmpty() {
        return sessions.isEmpty();
    }

    int size() {
        return sessions.size();
    }

    void clear() {
        sessions.clear();
        missingSince.clear();
    }
}
