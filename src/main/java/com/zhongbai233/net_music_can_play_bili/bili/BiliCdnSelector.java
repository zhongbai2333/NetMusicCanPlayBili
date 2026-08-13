package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnHealthTracker;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnProperties;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeClient;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import org.slf4j.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** B站 CDN 优选缓存与轻量首包竞速。 */
public final class BiliCdnSelector {
    private static final CdnProperties.Selector PROPERTIES = CdnProperties.selector();
    private static final boolean ENABLED = PROPERTIES.enabled();
    private static final boolean RACE_ENABLED = PROPERTIES.raceEnabled();
    private static final int RACE_BYTES = PROPERTIES.raceBytes();
    private static final long RACE_TIMEOUT_MILLIS = PROPERTIES.raceTimeoutMillis();
    private static final int MAX_RACE_CANDIDATES = PROPERTIES.maxRaceCandidates();
    private static final long MIN_PERSIST_INTERVAL_MILLIS = PROPERTIES.minPersistIntervalMillis();
    private static final long BACKGROUND_RACE_INTERVAL_MILLIS = PROPERTIES.backgroundRaceIntervalMillis();
    private static final Object PREFERENCE_LOCK = new Object();
    private static final AtomicLong LAST_BACKGROUND_RACE_AT = new AtomicLong();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(MAX_RACE_CANDIDATES,
            NetMusicThreadFactory.daemon("bili-cdn-selector"));

    private static volatile String preferredHost = PROPERTIES.preferredHost();
    private static volatile long preferredUpdatedAtMillis;

    private BiliCdnSelector() {
    }

    public static void load(JsonObject root) {
        if (root == null || !root.has("cdnPreference") || root.get("cdnPreference").isJsonNull()) {
            return;
        }
        try {
            JsonObject cdn = root.getAsJsonObject("cdnPreference");
            JsonElement host = cdn.get("preferredHost");
            if (host != null && !host.isJsonNull()) {
                preferredHost = normalizeHost(host.getAsString());
            }
            JsonElement updated = cdn.get("updatedAtMillis");
            if (updated != null && !updated.isJsonNull()) {
                preferredUpdatedAtMillis = Math.max(0L, updated.getAsLong());
            }
            if (!preferredHost.isBlank()) {
                logger().info("已加载 B站 CDN 优选域名: {}", preferredHost);
            }
        } catch (Exception e) {
            logger().warn("加载 B站 CDN 优选配置失败", e);
        }
    }

    public static void save(JsonObject root) {
        if (root == null) {
            return;
        }
        JsonObject cdn = new JsonObject();
        cdn.addProperty("preferredHost", preferredHost != null ? preferredHost : "");
        cdn.addProperty("updatedAtMillis", preferredUpdatedAtMillis);
        root.add("cdnPreference", cdn);
    }

    public static String selectPreferred(List<String> candidates) {
        List<String> ordered = orderCandidates(candidates);
        if (ordered.isEmpty()) {
            return "";
        }
        if (!ENABLED || !RACE_ENABLED || ordered.size() <= 1) {
            return ordered.get(0);
        }
        if (hasPreferredHost(ordered)) {
            refreshPreferredInBackground(ordered);
            return ordered.get(0);
        }
        String raced = raceFirstReadable(ordered);
        return raced != null && !raced.isBlank() ? raced : ordered.get(0);
    }

    public static List<String> orderCandidates(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> clean = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                clean.add(candidate);
            }
        }
        List<String> ordered = new ArrayList<>(clean);
        if (!ENABLED) {
            return ordered;
        }
        String preferred = preferredHost;
        if (!preferred.isBlank()) {
            ordered.sort(Comparator
                    .comparingInt((String url) -> preferred.equals(hostOf(url)) ? 0 : 1)
                    .thenComparingDouble(url -> {
                        try {
                            return CdnHealthTracker.score(URI.create(url).toURL());
                        } catch (Exception ignored) {
                            return 0.0D;
                        }
                    }));
            return ordered;
        }
        ordered.sort(Comparator
                .comparingDouble((String url) -> {
                    try {
                        return CdnHealthTracker.score(URI.create(url).toURL());
                    } catch (Exception ignored) {
                        return 0.0D;
                    }
                })
                .thenComparing(url -> hostOf(url)));
        return ordered;
    }

    public static boolean hasPreferredHost() {
        return ENABLED && preferredHost != null && !preferredHost.isBlank();
    }

    public static boolean hasPreferredHost(List<?> candidates) {
        if (!hasPreferredHost() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        String preferred = preferredHost;
        for (Object candidate : candidates) {
            String host = "";
            if (candidate instanceof URL url) {
                host = normalizeHost(url.getHost());
            } else if (candidate instanceof String value) {
                host = hostOf(value);
            }
            if (preferred.equals(host)) {
                return true;
            }
        }
        return false;
    }

    public static void recordSuccess(String url) {
        if (!ENABLED) {
            return;
        }
        String host = hostOf(url);
        if (host.isBlank() || host.equals(preferredHost)) {
            return;
        }
        synchronized (PREFERENCE_LOCK) {
            if (host.equals(preferredHost)) {
                return;
            }
            long now = System.currentTimeMillis();
            if (!preferredHost.isBlank() && now - preferredUpdatedAtMillis < MIN_PERSIST_INTERVAL_MILLIS) {
                logger().debug("B站 CDN 优选更新已防抖: current={} candidate={}", preferredHost, host);
                return;
            }
            preferredHost = host;
            preferredUpdatedAtMillis = now;
            BiliConfig.save();
            logger().info("B站 CDN 优选更新: host={}", host);
        }
    }

    private static String raceFirstReadable(List<String> candidates) {
        String winner = raceFirstReadable(candidates, true);
        return winner != null ? winner : "";
    }

    private static String raceFirstReadable(List<String> candidates, boolean persistWinner) {
        String winner = raceFirstReadableCandidate(candidates);
        if (persistWinner && winner != null && !winner.isBlank()) {
            recordSuccess(winner);
        }
        return winner;
    }

    private static void refreshPreferredInBackground(List<String> candidates) {
        long now = System.currentTimeMillis();
        long previous = LAST_BACKGROUND_RACE_AT.get();
        if (now - previous < BACKGROUND_RACE_INTERVAL_MILLIS
                || !LAST_BACKGROUND_RACE_AT.compareAndSet(previous, now)) {
            return;
        }
        List<String> snapshot = List.copyOf(candidates);
        Thread thread = NetMusicThreadFactory.daemonThread("bili-cdn-background-race", () -> {
            String winner = raceFirstReadable(snapshot, false);
            if (winner != null && !winner.isBlank()) {
                logger().debug("B站 CDN 后台优选完成: host={}", hostOf(winner));
            }
        });
        thread.start();
    }

    private static String raceFirstReadableCandidate(List<String> candidates) {
        int count = Math.min(MAX_RACE_CANDIDATES, candidates.size());
        CompletableFuture<String> first = new CompletableFuture<>();
        List<Future<?>> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String url = candidates.get(i);
            tasks.add(EXECUTOR.submit(() -> {
                if (first.isDone()) {
                    return;
                }
                if (probeReadable(url)) {
                    first.complete(url);
                }
            }, EXECUTOR));
        }
        try {
            return first.get(RACE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return "";
        } finally {
            tasks.forEach(task -> task.cancel(true));
        }
    }

    private static boolean probeReadable(String url) {
        long started = System.currentTimeMillis();
        try {
            URL parsed = URI.create(url).toURL();
            URL requestUrl = com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync.strip(parsed);
                try (HttpRangeClient.CdnResponse response = new HttpRangeClient().getRangeDirect(
                    requestUrl, 0L, RACE_BYTES - 1L)) {
                InputStream body = response.body();
                int status = response.statusCode();
                BiliRequestHeaders.recordBiliCdnResponse(parsed, status);
                if (status != 200 && status != 206) {
                    CdnHealthTracker.recordFailure(parsed, status == 403
                            ? CdnHealthTracker.FailureKind.HTTP_FORBIDDEN
                            : CdnHealthTracker.FailureKind.HTTP_RETRYABLE);
                    return false;
                }
                byte[] bytes = body.readNBytes(1);
                if (bytes.length > 0) {
                    CdnHealthTracker.recordSuccess(parsed, System.currentTimeMillis() - started,
                            response.contentLength() >= 0L ? response.contentLength() : bytes.length);
                    return true;
                }
                CdnHealthTracker.recordFailure(parsed, CdnHealthTracker.FailureKind.EMPTY);
                return false;
            }
        } catch (Exception e) {
            try {
                CdnHealthTracker.recordFailure(URI.create(url).toURL(), CdnHealthTracker.FailureKind.IO);
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private static String hostOf(String url) {
        try {
            return normalizeHost(URI.create(url).getHost());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizeHost(String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    }

    private static Logger logger() {
        return LoggerHolder.INSTANCE;
    }

    private static final class LoggerHolder {
        private static final Logger INSTANCE = LogUtils.getLogger();

        private LoggerHolder() {
        }
    }
}
