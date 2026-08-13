package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;

import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Short-lived cache for small fMP4 initialization and SIDX ranges reused by seek restarts. */
public final class Fmp4SeekRangeCache {
    private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_CACHED_BYTES = 1024 * 1024;
    private static final ConcurrentHashMap<Key, CachedRange> RANGES = new ConcurrentHashMap<>();

    private Fmp4SeekRangeCache() {
    }

    public static CachedRange get(URL url, long start, long endInclusive) {
        Key key = key(url, start, endInclusive);
        if (key == null) {
            return null;
        }
        CachedRange cached = RANGES.get(key);
        long now = System.currentTimeMillis();
        if (cached == null || cached.expiresAtMillis() < now) {
            if (cached != null) {
                RANGES.remove(key, cached);
            }
            return null;
        }
        return cached;
    }

    public static void put(URL url, long start, long endInclusive, byte[] bytes, long totalLength,
            String sourceHost, String sourceUrl) {
        Key key = key(url, start, endInclusive);
        long rangeWidth = endInclusive - start;
        if (key == null || rangeWidth < 0L || rangeWidth >= MAX_CACHED_BYTES || bytes == null
                || bytes.length != rangeWidth + 1L) {
            return;
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        RANGES.put(key, new CachedRange(bytes, totalLength,
                sourceHost != null ? sourceHost : "", sourceUrl != null ? sourceUrl : "",
                now + TTL_MILLIS));
        cleanup(now);
    }

    static void clearForTests() {
        RANGES.clear();
    }

    private static void cleanup(long now) {
        RANGES.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
        while (RANGES.size() > MAX_ENTRIES) {
            Key oldest = null;
            long oldestExpiry = Long.MAX_VALUE;
            for (var entry : RANGES.entrySet()) {
                if (entry.getValue().expiresAtMillis() < oldestExpiry) {
                    oldest = entry.getKey();
                    oldestExpiry = entry.getValue().expiresAtMillis();
                }
            }
            if (oldest == null) {
                return;
            }
            RANGES.remove(oldest);
        }
    }

    private static Key key(URL url, long start, long endInclusive) {
        if (url == null || start < 0L || endInclusive < start) {
            return null;
        }
        try {
            URI stripped = PlaybackSync.strip(url).toURI();
            String path = stripped.getRawPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            String query = stripped.getRawQuery();
            return new Key(path + (query != null ? "?" + query : ""), start, endInclusive);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record Key(String resource, long start, long endInclusive) {
    }

    public record CachedRange(byte[] bytes, long totalLength, String sourceHost, String sourceUrl,
            long expiresAtMillis) {
        public CachedRange {
            bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }
    }
}
