package com.zhongbai233.net_music_can_play_bili.media.sync;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 线程安全的一次性请求上下文注册表。
 *
 * <p>
 * 每个 token 只能成功消费一次；过期、取消或清空后都不可再次取得上下文。
 * </p>
 */
public final class OneShotRequestRegistry<T> {
    private final ConcurrentHashMap<String, Entry<T>> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final Supplier<String> tokenFactory;

    public OneShotRequestRegistry() {
        this(System::currentTimeMillis, () -> UUID.randomUUID().toString());
    }

    OneShotRequestRegistry(LongSupplier clock, Supplier<String> tokenFactory) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenFactory = Objects.requireNonNull(tokenFactory, "tokenFactory");
    }

    public String register(T value, long expiresAtMillis) {
        Objects.requireNonNull(value, "value");
        cleanupExpired();
        while (true) {
            String token = Objects.requireNonNull(tokenFactory.get(), "token");
            if (token.isBlank()) {
                throw new IllegalStateException("request token must not be blank");
            }
            if (entries.putIfAbsent(token, new Entry<>(value, expiresAtMillis)) == null) {
                return token;
            }
        }
    }

    public T consume(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Entry<T> entry = entries.remove(token);
        return entry != null && entry.expiresAtMillis() >= clock.getAsLong() ? entry.value() : null;
    }

    public boolean contains(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Entry<T> entry = entries.get(token);
        if (entry == null) {
            return false;
        }
        if (entry.expiresAtMillis() >= clock.getAsLong()) {
            return true;
        }
        entries.remove(token, entry);
        return false;
    }

    public void cancel(String token) {
        if (token != null && !token.isBlank()) {
            entries.remove(token);
        }
    }

    public void cleanupExpired() {
        long now = clock.getAsLong();
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
    }

    public void clear() {
        entries.clear();
    }

    private record Entry<T>(T value, long expiresAtMillis) {
    }
}