package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 将同一 DASH 流中等价的 CDN URL 归为一组。
 * <p>
 * Bilibili 通常会为同一媒体轨道返回一个基础 URL 和多个备用 URL。首个主机在用户网络中可能较慢或不可达，
 * 因此范围读取器应先用同组 URL 重试同一字节范围，再放弃整个音频流。
 */
public final class CdnUrlFallbacks {
    private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final int MAX_GROUPS = Integer.getInteger("ncpb.bili.cdn_fallback.max_groups", 512);
    private static final ConcurrentHashMap<String, UrlGroup> GROUPS_BY_URL = new ConcurrentHashMap<>();

    private CdnUrlFallbacks() {
    }

    public static void registerAlternates(List<String> urls) {
        if (urls == null || urls.size() <= 1) {
            return;
        }
        Set<String> clean = new LinkedHashSet<>();
        for (String url : urls) {
            String key = key(url);
            if (key != null) {
                clean.add(key);
            }
        }
        if (clean.size() <= 1) {
            return;
        }

        long now = System.currentTimeMillis();
        cleanup(now);
        UrlGroup group = new UrlGroup(List.copyOf(clean), now + TTL_MILLIS);
        for (String url : clean) {
            GROUPS_BY_URL.put(url, group);
        }
        cleanup(now);
    }

    public static List<URL> candidates(URL primary) {
        String primaryKey = key(primary);
        if (primaryKey == null) {
            return List.of(primary);
        }

        UrlGroup group = GROUPS_BY_URL.get(primaryKey);
        if (group == null || group.expiresAtMillis() < System.currentTimeMillis()) {
            GROUPS_BY_URL.remove(primaryKey, group);
            return List.of(primary);
        }

        List<URL> result = new ArrayList<>(group.urls().size());
        addUrl(result, primaryKey);
        for (String url : group.urls()) {
            if (!url.equals(primaryKey)) {
                addUrl(result, url);
            }
        }
        return result.isEmpty() ? List.of(primary) : result;
    }

    private static void addUrl(List<URL> result, String value) {
        try {
            result.add(URI.create(value).toURL());
        } catch (Exception ignored) {
        }
    }

    private static void cleanup(long now) {
        GROUPS_BY_URL.forEach((url, group) -> {
            if (group.expiresAtMillis() < now) {
                GROUPS_BY_URL.remove(url, group);
            }
        });
        int maxEntries = Math.max(1, MAX_GROUPS);
        while (GROUPS_BY_URL.size() > maxEntries) {
            UrlGroup oldest = null;
            for (UrlGroup group : GROUPS_BY_URL.values()) {
                if (oldest == null || group.expiresAtMillis() < oldest.expiresAtMillis()) {
                    oldest = group;
                }
            }
            if (oldest == null) {
                return;
            }
            UrlGroup groupToRemove = oldest;
            GROUPS_BY_URL.entrySet().removeIf(entry -> entry.getValue() == groupToRemove);
        }
    }

    private static String key(URL url) {
        return url == null ? null : key(url.toString());
    }

    private static String key(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return PlaybackSync.strip(url).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private record UrlGroup(List<String> urls, long expiresAtMillis) {
    }
}