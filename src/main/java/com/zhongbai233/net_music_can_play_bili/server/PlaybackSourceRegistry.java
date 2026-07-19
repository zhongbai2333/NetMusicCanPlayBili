package com.zhongbai233.net_music_can_play_bili.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Thread-safe active playback source storage without game-specific
 * dependencies.
 */
final class PlaybackSourceRegistry<T> {
    private final ConcurrentHashMap<String, T> sources = new ConcurrentHashMap<>();

    void put(String key, T source) {
        sources.put(key, source);
    }

    T get(String key) {
        return sources.get(key);
    }

    List<T> snapshot() {
        return new ArrayList<>(sources.values());
    }

    void removeIf(Predicate<T> predicate) {
        sources.values().removeIf(predicate);
    }

    Set<String> keys() {
        return Set.copyOf(sources.keySet());
    }
}