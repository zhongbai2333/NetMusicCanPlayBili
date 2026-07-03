package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量 CDN 健康评分器。
 *
 * <p>
 * 只在客户端进程内维护近期状态：成功会逐步降低惩罚，失败/空包/短读会增加惩罚，
 * 候选 CDN 排序时优先选择低惩罚、近期成功、延迟更低的 host。
 * </p>
 */
public final class CdnHealthTracker {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("ncpb.bili.cdn_health.enabled", "true"));
    private static final long STALE_AFTER_MILLIS = Math.max(10_000L, Long.getLong(
            "bili.cdn_health.stale_after_ms", 10L * 60L * 1000L));
    private static final double SUCCESS_DECAY = clamp01(parseDouble("bili.cdn_health.success_decay", 0.72D));
    private static final double FAILURE_PENALTY = Math.max(0.0D,
            parseDouble("bili.cdn_health.failure_penalty", 4.0D));
    private static final double EMPTY_PENALTY = Math.max(0.0D,
            parseDouble("bili.cdn_health.empty_penalty", 6.0D));
    private static final double SHORT_READ_PENALTY = Math.max(0.0D,
            parseDouble("bili.cdn_health.short_read_penalty", 3.0D));
    private static final double HTTP_RETRYABLE_PENALTY = Math.max(0.0D,
            parseDouble("bili.cdn_health.http_retryable_penalty", 5.0D));
    private static final double MAX_PENALTY = Math.max(1.0D,
            parseDouble("bili.cdn_health.max_penalty", 64.0D));

    private static final ConcurrentHashMap<String, HostHealth> HEALTH_BY_HOST = new ConcurrentHashMap<>();

    private CdnHealthTracker() {
    }

    public static void recordSuccess(URL url, long elapsedMillis, long bytes) {
        if (!ENABLED) {
            return;
        }
        String host = host(url);
        if (host == null) {
            return;
        }
        long now = System.currentTimeMillis();
        HEALTH_BY_HOST.compute(host, (ignored, existing) -> {
            HostHealth health = existing != null ? existing.fresh(now) : HostHealth.initial(now);
            double latency = elapsedMillis > 0L ? elapsedMillis : health.latencyMillis();
            double ewmaLatency = health.latencyMillis() <= 0.0D
                    ? latency
                    : health.latencyMillis() * 0.8D + latency * 0.2D;
            double penalty = Math.max(0.0D, health.penalty() * SUCCESS_DECAY - 0.25D);
            return new HostHealth(penalty, ewmaLatency, health.successes() + 1, health.failures(), now);
        });
    }

    public static void recordFailure(URL url, FailureKind kind) {
        if (!ENABLED) {
            return;
        }
        String host = host(url);
        if (host == null) {
            return;
        }
        double delta = switch (kind) {
            case EMPTY -> EMPTY_PENALTY;
            case SHORT_READ -> SHORT_READ_PENALTY;
            case HTTP_RETRYABLE -> HTTP_RETRYABLE_PENALTY;
            case IO -> FAILURE_PENALTY;
        };
        long now = System.currentTimeMillis();
        HEALTH_BY_HOST.compute(host, (ignored, existing) -> {
            HostHealth health = existing != null ? existing.fresh(now) : HostHealth.initial(now);
            double penalty = Math.min(MAX_PENALTY, health.penalty() + delta);
            return new HostHealth(penalty, health.latencyMillis(), health.successes(), health.failures() + 1, now);
        });
    }

    public static double score(URL url) {
        if (!ENABLED) {
            return 0.0D;
        }
        String host = host(url);
        if (host == null) {
            return 0.0D;
        }
        HostHealth health = HEALTH_BY_HOST.get(host);
        if (health == null) {
            return 0.0D;
        }
        long age = Math.max(0L, System.currentTimeMillis() - health.updatedAtMillis());
        double ageDiscount = age >= STALE_AFTER_MILLIS ? 0.0D : 1.0D - age / (double) STALE_AFTER_MILLIS;
        double latencyPenalty = health.latencyMillis() > 0.0D ? Math.min(8.0D, health.latencyMillis() / 750.0D) : 0.0D;
        return health.penalty() * ageDiscount + latencyPenalty * ageDiscount;
    }

    public static void clear() {
        HEALTH_BY_HOST.clear();
    }

    private static String host(URL url) {
        String host = url != null ? url.getHost() : null;
        return host == null || host.isBlank() ? null : host.toLowerCase(java.util.Locale.ROOT);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.72D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double parseDouble(String key, double fallback) {
        try {
            double value = Double.parseDouble(System.getProperty(key, Double.toString(fallback)));
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public enum FailureKind {
        IO,
        EMPTY,
        SHORT_READ,
        HTTP_RETRYABLE
    }

    private record HostHealth(double penalty, double latencyMillis, long successes, long failures,
            long updatedAtMillis) {
        static HostHealth initial(long now) {
            return new HostHealth(0.0D, 0.0D, 0L, 0L, now);
        }

        HostHealth fresh(long now) {
            long age = Math.max(0L, now - updatedAtMillis);
            if (age <= 0L) {
                return this;
            }
            double staleRatio = Math.min(1.0D, age / (double) STALE_AFTER_MILLIS);
            return new HostHealth(penalty * (1.0D - staleRatio * 0.5D), latencyMillis, successes, failures,
                    updatedAtMillis);
        }
    }
}
