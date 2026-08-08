package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToLongFunction;

/** 按估算字节权重淘汰的 LRU；单对象超限时直接拒绝，始终保持总权重不越界。 */
public final class WeightedLruCache<K, V> {
    private final long maxWeight;
    private final ToLongFunction<? super V> weightFunction;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75F, true);
    private long totalWeight;

    public WeightedLruCache(long maxWeight, ToLongFunction<? super V> weightFunction) {
        if (maxWeight <= 0L) {
            throw new IllegalArgumentException("cache maxWeight must be positive");
        }
        this.maxWeight = maxWeight;
        this.weightFunction = java.util.Objects.requireNonNull(weightFunction, "weightFunction");
    }

    public synchronized Optional<V> get(K key) {
        Entry<V> entry = entries.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.value());
    }

    public synchronized boolean put(K key, V value) {
        java.util.Objects.requireNonNull(key, "key");
        java.util.Objects.requireNonNull(value, "value");
        long weight = weightFunction.applyAsLong(value);
        if (weight < 0L) {
            throw new IllegalArgumentException("cache entry weight must be non-negative");
        }
        if (weight > maxWeight) {
            return false;
        }
        Entry<V> old = entries.remove(key);
        if (old != null) {
            totalWeight -= old.weight();
        }
        entries.put(key, new Entry<>(value, weight));
        totalWeight += weight;
        evictToBudget();
        return true;
    }

    public synchronized Optional<V> remove(K key) {
        Entry<V> removed = entries.remove(key);
        if (removed == null) {
            return Optional.empty();
        }
        totalWeight -= removed.weight();
        return Optional.of(removed.value());
    }

    public synchronized Map<K, V> snapshot() {
        LinkedHashMap<K, V> copy = new LinkedHashMap<>();
        entries.forEach((key, entry) -> copy.put(key, entry.value()));
        return Map.copyOf(copy);
    }

    public synchronized long totalWeight() {
        return totalWeight;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
        totalWeight = 0L;
    }

    private void evictToBudget() {
        var iterator = entries.entrySet().iterator();
        while (totalWeight > maxWeight && iterator.hasNext()) {
            totalWeight -= iterator.next().getValue().weight();
            iterator.remove();
        }
    }

    private record Entry<V>(V value, long weight) {
    }
}