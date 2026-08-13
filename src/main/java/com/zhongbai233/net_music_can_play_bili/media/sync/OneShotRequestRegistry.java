package com.zhongbai233.net_music_can_play_bili.media.sync;

import java.util.Objects;
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
    private final ConcurrentHashMap<MediaRequestToken, Entry<T>> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final Supplier<MediaRequestToken> tokenFactory;

    public OneShotRequestRegistry() {
        this(System::currentTimeMillis, MediaRequestToken::random);
    }

    OneShotRequestRegistry(LongSupplier clock, Supplier<MediaRequestToken> tokenFactory) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenFactory = Objects.requireNonNull(tokenFactory, "tokenFactory");
    }

    public String register(T value, long expiresAtMillis) {
        return registerToken(value, expiresAtMillis).value();
    }

    public MediaRequestToken registerToken(T value, long expiresAtMillis) {
        Objects.requireNonNull(value, "value");
        cleanupExpired();
        while (true) {
            MediaRequestToken token = Objects.requireNonNull(tokenFactory.get(), "token");
            if (entries.putIfAbsent(token, new Entry<>(value, expiresAtMillis)) == null) {
                return token;
            }
        }
    }

    public T consume(String token) {
        return MediaRequestToken.parse(token).map(this::consumeToken).orElse(null);
    }

    public T consumeToken(MediaRequestToken token) {
        if (token == null) {
            return null;
        }
        Entry<T> entry = entries.remove(token);
        return entry != null && entry.expiresAtMillis() >= clock.getAsLong() ? entry.value() : null;
    }

    public boolean contains(String token) {
        return MediaRequestToken.parse(token).map(this::containsToken).orElse(false);
    }

    public boolean containsToken(MediaRequestToken token) {
        if (token == null) {
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
        MediaRequestToken.parse(token).ifPresent(this::cancelToken);
    }

    public void cancelToken(MediaRequestToken token) {
        if (token != null) {
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
