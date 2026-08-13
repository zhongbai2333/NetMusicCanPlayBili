package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

/** JVM property boundary for advanced video features, benchmarks, and decoder overrides. */
public final class VideoFeatureProperties {
    static final String ADVANCED_FEATURES = "ncpb.video.advanced_features";
    static final String BENCH_FEATURES = "ncpb.video.enable_bench_features";
    static final String NATIVE_HWACCEL = "ncpb.video.native.hwaccel";
    static final String REAL_BENCH = "ncpb.video.real_bench";
    static final String REAL_BENCH_MANAGED = "ncpb.video.real_bench.managed";
    static final String REAL_MEDIA_LIFECYCLE = "ncpb.video.real_media_lifecycle";
    static final String REAL_MEDIA_LIFECYCLE_ROUNDS = "ncpb.video.real_media_lifecycle.rounds";
    static final String REAL_MEDIA_LIFECYCLE_CYCLE_TIMEOUT_TICKS =
            "ncpb.video.real_media_lifecycle.cycle_timeout_ticks";
    static final String REAL_BENCH_VIDEO_ID = "ncpb.video.real_bench.bv";
    static final String REAL_MEDIA_LIFECYCLE_QUALITY = "ncpb.video.real_media_lifecycle.quality";
    static final String FFMPEG_DECODER = "ncpb.video.ffmpeg.decoder";

    private VideoFeatureProperties() {
    }

    static boolean advancedFeaturesEnabled() {
        return NcpbSystemProperties.booleanValue(ADVANCED_FEATURES, false);
    }

    static boolean benchFeaturesEnabled() {
        return NcpbSystemProperties.booleanValue(BENCH_FEATURES, false);
    }

    public static boolean realBenchEnabled() {
        return NcpbSystemProperties.booleanValue(REAL_BENCH, false);
    }

    /** Explicit opt-in configuration for the expensive real Bilibili loaded lifecycle matrix. */
    public static RealMediaLifecycle realMediaLifecycle() {
        return new RealMediaLifecycle(
                NcpbSystemProperties.booleanValue(REAL_MEDIA_LIFECYCLE, false),
                NcpbSystemProperties.intValue(REAL_MEDIA_LIFECYCLE_ROUNDS, 100),
                NcpbSystemProperties.intValue(REAL_MEDIA_LIFECYCLE_CYCLE_TIMEOUT_TICKS, 600),
                NcpbSystemProperties.stringValue(REAL_BENCH_VIDEO_ID, "BV1qM4y1w716"),
                NcpbSystemProperties.intValue(REAL_MEDIA_LIFECYCLE_QUALITY, 16));
    }

    static boolean realBenchManaged() {
        return NcpbSystemProperties.booleanValue(REAL_BENCH_MANAGED, false);
    }

    static boolean booleanValue(String key, boolean fallback) {
        return NcpbSystemProperties.booleanValue(key, fallback);
    }

    static int intValue(String key, int fallback) {
        return NcpbSystemProperties.intValue(key, fallback);
    }

    static long longValue(String key, long fallback) {
        return NcpbSystemProperties.longValue(key, fallback);
    }

    static String stringValue(String key, String fallback) {
        return NcpbSystemProperties.stringValue(key, fallback);
    }

    static String nativeHwaccel() {
        return NcpbSystemProperties.stringValue(NATIVE_HWACCEL, "auto");
    }

    static String ffmpegDecoder() {
        return NcpbSystemProperties.stringValue(FFMPEG_DECODER, "");
    }

    public record RealMediaLifecycle(boolean enabled, int rounds, int cycleTimeoutTicks,
            String videoId, int quality) {
        public RealMediaLifecycle {
            rounds = Math.clamp(rounds, 1, 10_000);
            cycleTimeoutTicks = Math.clamp(cycleTimeoutTicks, 100, 72_000);
            videoId = videoId != null && !videoId.isBlank() ? videoId.trim() : "BV1qM4y1w716";
            quality = Math.max(1, quality);
        }
    }
}
