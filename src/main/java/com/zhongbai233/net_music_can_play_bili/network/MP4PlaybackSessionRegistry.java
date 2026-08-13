package com.zhongbai233.net_music_can_play_bili.network;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/** Owns active MP4 playback sessions and their missing-source grace periods. */
final class MP4PlaybackSessionRegistry<K, S> {
    private final ConcurrentMap<K, S> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<K, Long> missingSince = new ConcurrentHashMap<>();

    S get(K key) {
        return key != null ? sessions.get(key) : null;
    }

    boolean contains(K key) {
        return key != null && sessions.containsKey(key);
    }

    S replace(K key, S session) {
        K requiredKey = Objects.requireNonNull(key, "key");
        S requiredSession = Objects.requireNonNull(session, "session");
        S previous = sessions.put(requiredKey, requiredSession);
        missingSince.remove(requiredKey);
        return previous;
    }

    boolean replace(K key, S expected, S replacement) {
        if (key == null || expected == null || replacement == null) {
            return false;
        }
        AtomicBoolean replaced = new AtomicBoolean();
        sessions.computeIfPresent(key, (ignored, current) -> {
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

    S updateIfPresent(K key, UnaryOperator<S> updater) {
        if (key == null) {
            return null;
        }
        Objects.requireNonNull(updater, "updater");
        return sessions.computeIfPresent(key,
                (ignored, current) -> Objects.requireNonNull(updater.apply(current), "updated session"));
    }

    S remove(K key) {
        if (key == null) {
            return null;
        }
        S removed = sessions.remove(key);
        missingSince.remove(key);
        return removed;
    }

    boolean remove(K key, S expected) {
        if (key == null || expected == null) {
            return false;
        }
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

    Long markMissingIfCurrent(K key, S expected, long gameTime) {
        if (key == null || expected == null) {
            return null;
        }
        AtomicReference<Long> result = new AtomicReference<>();
        sessions.computeIfPresent(key, (ignored, current) -> {
            if (current == expected) {
                result.set(missingSince.computeIfAbsent(key, missing -> gameTime));
            }
            return current;
        });
        return result.get();
    }

    boolean clearMissingIfCurrent(K key, S expected) {
        if (key == null || expected == null) {
            return false;
        }
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

    List<Map.Entry<K, S>> entries() {
        return sessions.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
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
