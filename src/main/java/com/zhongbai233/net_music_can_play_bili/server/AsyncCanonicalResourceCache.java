package com.zhongbai233.net_music_can_play_bili.server;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Bounded, single-flight identity cache. Authorization decisions are never cached. */
final class AsyncCanonicalResourceCache<V> {
    private final int capacity;
    private final long successTtl, failureTtl;
    private final LongSupplier clock;
    private final Executor executor;
    private final Function<String, Optional<V>> loader;
    private final LinkedHashMap<String, Entry<V>> entries = new LinkedHashMap<>();
    private long generation;

    AsyncCanonicalResourceCache(int capacity, long successTtl, long failureTtl, LongSupplier clock,
            Executor executor, Function<String, Optional<V>> loader) {
        this.capacity = capacity;
        this.successTtl = successTtl;
        this.failureTtl = failureTtl;
        this.clock = clock;
        this.executor = executor;
        this.loader = loader;
    }

    synchronized Optional<V> peek(String key) {
        Entry<V> entry = entries.get(key);
        if (entry == null || !entry.future.isDone() || expired(entry)) return Optional.empty();
        return entry.future.getNow(Optional.empty());
    }

    synchronized boolean ready(String key) {
        Entry<V> entry = entries.get(key);
        return entry != null && entry.future.isDone() && !expired(entry);
    }

    CompletableFuture<Optional<V>> resolve(String key) {
        Entry<V> entry;
        synchronized (this) {
            entries.entrySet().removeIf(e -> expired(e.getValue()));
            entry = entries.get(key);
            if (entry != null) return entry.future.copy();
            if (entries.size() >= capacity) {
                String completed = entries.entrySet().stream().filter(e -> e.getValue().future.isDone())
                        .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
                if (completed == null) return CompletableFuture.completedFuture(Optional.empty());
                entries.remove(completed);
            }
            entry = new Entry<>(generation);
            entries.put(key, entry);
        }
        Entry<V> loading = entry;
        try {
            executor.execute(() -> {
                boolean obsolete;
                synchronized (this) { obsolete = loading.generation != generation; }
                if (obsolete) {
                    loading.future.complete(Optional.empty());
                    return;
                }
                Optional<V> value;
                try { value = loader.apply(key); } catch (RuntimeException e) { value = Optional.empty(); }
                if (value == null) value = Optional.empty();
                synchronized (this) {
                    loading.expiresAt = clock.getAsLong() + (value.isPresent() ? successTtl : failureTtl);
                    // Clear cannot resurrect an old result into the new cache generation.
                    if (loading.generation != generation) entries.remove(key, loading);
                }
                loading.future.complete(value);
            });
        } catch (RuntimeException rejected) {
            synchronized (this) {
                loading.expiresAt = clock.getAsLong() + failureTtl;
            }
            loading.future.complete(Optional.empty());
        }
        return loading.future.copy();
    }

    synchronized void clear() { generation++; entries.clear(); }

    private boolean expired(Entry<V> entry) {
        return entry.future.isDone() && clock.getAsLong() >= entry.expiresAt;
    }

    private static final class Entry<V> {
        final CompletableFuture<Optional<V>> future = new CompletableFuture<>();
        final long generation;
        long expiresAt = Long.MAX_VALUE;
        Entry(long generation) { this.generation = generation; }
    }
}
