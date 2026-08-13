package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

/** JVM property boundary for HTTP audio, Dolby queues, live video samples, and stream recovery. */
public final class AudioStreamProperties {
    static final String FORMAT_WAIT_SECONDS = "ncpb.bili.media.format_wait_seconds";
    static final String RANGE_RACE_MAX_CANDIDATES = "ncpb.bili.audio.range_race.max_candidates";
    static final String RANGE_RACE_TIMEOUT_MILLIS = "ncpb.bili.audio.range_race.timeout_ms";
    static final String SEGMENT_BASE_CACHE_MAX_ENTRIES =
            "ncpb.bili.audio.segment_base_cache.max_entries";
    static final String LEGACY_SEGMENT_BASE_CACHE_MAX_ENTRIES =
            "bili.audio.segment_base_cache.max_entries";
    static final String DOLBY_PREBUFFER_FRAMES = "ncpb.bili.audio.openal.dolby_prebuffer_frames";
    static final String DOLBY_RAW_QUEUE_CAPACITY = "ncpb.bili.audio.dolby.raw_queue_capacity";
    static final String DOLBY_PROCESSED_QUEUE_CAPACITY =
            "ncpb.bili.audio.dolby.processed_queue_capacity";
    static final String LIVE_VIDEO_BUS_CAPACITY = "ncpb.bili.live.video.bus_capacity";
    static final String STREAM_RECOVERY_MAX_ATTEMPTS =
            "ncpb.bili.media.stream_recovery.max_attempts";
    static final String STREAM_RECOVERY_MIN_INTERVAL_MILLIS =
            "ncpb.bili.media.stream_recovery.min_interval_ms";
    static final String REAL_MP3_BENCH = "ncpb.bili.audio.real_mp3_bench";
    static final String REAL_MP3_BENCH_URL = "ncpb.bili.audio.real_mp3_bench.url";
    private static final String DEFAULT_REAL_MP3_BENCH_URL =
            "https://www.learningcontainer.com/wp-content/uploads/2020/02/Kalimba.mp3";

    private AudioStreamProperties() {
    }

    public static Http http() {
        return new Http(
                NcpbSystemProperties.intValue(FORMAT_WAIT_SECONDS, 30),
                NcpbSystemProperties.intValue(RANGE_RACE_MAX_CANDIDATES, 1),
                NcpbSystemProperties.longValue(RANGE_RACE_TIMEOUT_MILLIS, 2_500L),
                NcpbSystemProperties.intValue(
                        SEGMENT_BASE_CACHE_MAX_ENTRIES, LEGACY_SEGMENT_BASE_CACHE_MAX_ENTRIES, 512));
    }

    public static Dolby dolby() {
        int prebufferFrames = Math.max(0, NcpbSystemProperties.intValue(DOLBY_PREBUFFER_FRAMES, 8));
        return new Dolby(
                prebufferFrames,
                NcpbSystemProperties.intValue(DOLBY_RAW_QUEUE_CAPACITY, 128),
                NcpbSystemProperties.intValue(DOLBY_PROCESSED_QUEUE_CAPACITY, 64));
    }

    public static int liveVideoBusCapacity() {
        return Math.max(32, NcpbSystemProperties.intValue(LIVE_VIDEO_BUS_CAPACITY, 256));
    }

    public static Recovery recovery() {
        return new Recovery(
                NcpbSystemProperties.intValue(STREAM_RECOVERY_MAX_ATTEMPTS, 3),
                NcpbSystemProperties.longValue(STREAM_RECOVERY_MIN_INTERVAL_MILLIS, 1_000L));
    }

    public static RealMp3Bench realMp3Bench() {
        return new RealMp3Bench(
                NcpbSystemProperties.booleanValue(REAL_MP3_BENCH, false),
                NcpbSystemProperties.stringValue(REAL_MP3_BENCH_URL, DEFAULT_REAL_MP3_BENCH_URL));
    }

    public record Http(int formatWaitSeconds, int rangeRaceMaxCandidates,
            long rangeRaceTimeoutMillis, int segmentBaseCacheMaxEntries) {
        public Http {
            formatWaitSeconds = Math.max(15, formatWaitSeconds);
            rangeRaceMaxCandidates = Math.max(1, rangeRaceMaxCandidates);
            rangeRaceTimeoutMillis = Math.max(250L, rangeRaceTimeoutMillis);
            segmentBaseCacheMaxEntries = Math.max(1, segmentBaseCacheMaxEntries);
        }
    }

    public record Dolby(int prebufferFrames, int rawQueueCapacity, int processedQueueCapacity) {
        public Dolby {
            prebufferFrames = Math.max(0, prebufferFrames);
            rawQueueCapacity = Math.max(32, rawQueueCapacity);
            processedQueueCapacity = Math.max(Math.max(1, prebufferFrames), processedQueueCapacity);
        }
    }

    public record Recovery(int maxAttempts, long minIntervalMillis) {
        public Recovery {
            maxAttempts = Math.max(0, maxAttempts);
            minIntervalMillis = Math.max(0L, minIntervalMillis);
        }
    }

    public record RealMp3Bench(boolean enabled, String url) {
        public RealMp3Bench {
            url = url != null ? url.trim() : "";
            if (url.isBlank()) {
                url = DEFAULT_REAL_MP3_BENCH_URL;
            }
        }
    }
}
