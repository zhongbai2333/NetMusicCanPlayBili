package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Owns session-keyed video instances and disposes them on replacement or removal. */
final class VideoSessionInstanceRegistry<T> {
    private final Map<Optional<PlaybackSessionId>, T> instances = new ConcurrentHashMap<>();
    private final Consumer<? super T> disposer;

    VideoSessionInstanceRegistry(Consumer<? super T> disposer) {
        this.disposer = Objects.requireNonNull(disposer, "disposer");
    }

    T get(String sessionId) {
        return instances.get(key(sessionId));
    }

    void replace(String sessionId, T instance) {
        T next = Objects.requireNonNull(instance, "instance");
        T previous = instances.put(key(sessionId), next);
        if (previous != null && previous != next) {
            disposer.accept(previous);
        }
    }

    T remove(String sessionId) {
        T removed = instances.remove(key(sessionId));
        if (removed != null) {
            disposer.accept(removed);
        }
        return removed;
    }

    boolean remove(String sessionId, T expected) {
        if (expected == null || !instances.remove(key(sessionId), expected)) {
            return false;
        }
        disposer.accept(expected);
        return true;
    }

    void removeIf(Predicate<? super T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        for (Map.Entry<Optional<PlaybackSessionId>, T> entry : instances.entrySet()) {
            T instance = entry.getValue();
            if (predicate.test(instance) && instances.remove(entry.getKey(), instance)) {
                disposer.accept(instance);
            }
        }
    }

    void clear() {
        for (Map.Entry<Optional<PlaybackSessionId>, T> entry : instances.entrySet()) {
            T instance = entry.getValue();
            if (instances.remove(entry.getKey(), instance)) {
                disposer.accept(instance);
            }
        }
    }

    List<T> instances() {
        return List.copyOf(instances.values());
    }

    void forEach(Consumer<? super T> action) {
        instances().forEach(action);
    }

    int size() {
        return instances.size();
    }

    boolean isEmpty() {
        return instances.isEmpty();
    }

    private static Optional<PlaybackSessionId> key(String sessionId) {
        return PlaybackSessionId.parse(sessionId);
    }
}
