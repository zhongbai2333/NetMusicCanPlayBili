package com.zhongbai233.net_music_can_play_bili.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 服务端自定义包限频器，防止异常客户端刷包。 */
final class NetworkRateLimiter {
    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final Map<Key, Window> WINDOWS = new ConcurrentHashMap<>();

    private NetworkRateLimiter() {
    }

    static boolean allow(UUID playerId, String channel, int maxPerSecond) {
        if (playerId == null || channel == null || channel.isBlank()) {
            return false;
        }
        int limit = Math.max(1, maxPerSecond);
        long now = System.nanoTime();
        Window window = WINDOWS.computeIfAbsent(new Key(playerId, channel), ignored -> new Window(now));
        synchronized (window) {
            if (now - window.windowStartNanos >= WINDOW_NANOS) {
                window.windowStartNanos = now;
                window.count = 0;
            }
            if (window.count >= limit) {
                return false;
            }
            window.count++;
            return true;
        }
    }

    private record Key(UUID playerId, String channel) {
    }

    private static final class Window {
        private long windowStartNanos;
        private int count;

        private Window(long windowStartNanos) {
            this.windowStartNanos = windowStartNanos;
        }
    }
}
