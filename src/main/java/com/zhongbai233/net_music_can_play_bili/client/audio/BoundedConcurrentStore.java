package com.zhongbai233.net_music_can_play_bili.client.audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 小容量资源表：在同一临界区内完成按 key 替换和最旧项淘汰。
 *
 * <p>
 * 容器只返回被移除的值，不在锁内执行资源清理，因而不依赖 Minecraft/OpenAL，
 * 也不会让慢速 native cleanup 阻塞其它注册操作。
 * </p>
 */
final class BoundedConcurrentStore<K, V> {
    private final int capacity;
    private final Map<K, Slot<V>> entries = new HashMap<>();
    private long sequence;

    BoundedConcurrentStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    synchronized PutResult<V> put(K key, V value, long createdAtMillis) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Slot<V> replaced = entries.put(key, new Slot<>(value, createdAtMillis, sequence++));
        List<V> evicted = new ArrayList<>();
        while (entries.size() > capacity) {
            K oldestKey = null;
            Slot<V> oldest = null;
            for (Map.Entry<K, Slot<V>> candidate : entries.entrySet()) {
                if (oldest == null || candidate.getValue().olderThan(oldest)) {
                    oldestKey = candidate.getKey();
                    oldest = candidate.getValue();
                }
            }
            if (oldestKey == null || oldest == null) {
                break;
            }
            entries.remove(oldestKey);
            evicted.add(oldest.value());
        }
        return new PutResult<>(replaced != null ? replaced.value() : null, List.copyOf(evicted));
    }

    synchronized V get(K key) {
        Slot<V> slot = entries.get(key);
        return slot != null ? slot.value() : null;
    }

    synchronized boolean remove(K key, V expectedValue) {
        Slot<V> current = entries.get(key);
        if (current == null || current.value() != expectedValue) {
            return false;
        }
        entries.remove(key);
        return true;
    }

    synchronized List<V> removeIf(Predicate<? super V> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        List<V> removed = new ArrayList<>();
        entries.entrySet().removeIf(entry -> {
            if (!predicate.test(entry.getValue().value())) {
                return false;
            }
            removed.add(entry.getValue().value());
            return true;
        });
        return List.copyOf(removed);
    }

    synchronized List<V> values() {
        return entries.values().stream().map(slot -> slot.value()).toList();
    }

    synchronized List<V> clear() {
        List<V> removed = entries.values().stream().map(slot -> slot.value()).toList();
        entries.clear();
        return removed;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    record PutResult<V>(V replaced, List<V> evicted) {
    }

    private record Slot<V>(V value, long createdAtMillis, long sequence) {
        private boolean olderThan(Slot<V> other) {
            return createdAtMillis < other.createdAtMillis
                    || createdAtMillis == other.createdAtMillis && sequence < other.sequence;
        }
    }
}