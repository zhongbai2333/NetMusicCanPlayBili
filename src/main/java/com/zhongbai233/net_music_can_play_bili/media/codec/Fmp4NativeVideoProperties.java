package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

/** JVM property boundary for native fMP4 video buffering, recovery, and range seek. */
final class Fmp4NativeVideoProperties {
    static final String MAX_PENDING_FRAMES = "ncpb.video.native.max_pending_frames";
    static final String INIT_PROBE_BYTES = "ncpb.video.native.seek.init_probe_bytes";
    static final String MOOF_SCAN_BYTES = "ncpb.video.native.seek.moof_scan_bytes";
    static final String SEEK_MAX_ATTEMPTS = "ncpb.video.native.seek.max_attempts";
    static final String SEEK_PREROLL_BYTES = "ncpb.video.native.seek.preroll_bytes";
    static final String CLOSE_FRAGMENT_SECONDS = "ncpb.video.native.seek.close_fragment_seconds";
    static final String TARGET_EPSILON_SECONDS = "ncpb.video.native.seek.target_epsilon_seconds";
    static final String SEEK_LEAD_SECONDS = "ncpb.video.native.seek.lead_seconds";
    static final String RANGE_SEEK_ENABLED = "ncpb.video.native.seek.enabled";
    static final String RANGE_SEEK_AUTO_OFFSET_MILLIS = "ncpb.video.native.seek.auto_offset_ms";
    static final String LEGACY_RANGE_SEEK_AUTO_OFFSET_MILLIS = "bili.video.native.seek.auto_offset_ms";
    static final String FALLBACK_MAX_RESIDUAL_SECONDS =
            "ncpb.video.native.seek.fallback_max_residual_seconds";
    static final String NO_COPY_DROP_GUARD_MILLIS = "ncpb.video.native.seek.no_copy_drop_guard_ms";
    static final String LEGACY_NO_COPY_DROP_GUARD_MILLIS = "bili.video.native.seek.no_copy_drop_guard_ms";
    static final String STREAM_RECOVERY_ATTEMPTS = "ncpb.video.native.stream_recovery_attempts";
    static final String LEGACY_STREAM_RECOVERY_ATTEMPTS = "bili.video.native.stream_recovery_attempts";
    static final String REUSE_OUTPUT_BUFFERS = "ncpb.video.native.reuse_output_buffers";
    static final String DIRECT_NV12_BUFFERS = "ncpb.video.native.direct_nv12_buffers";
    static final String AV1_FIRST_FRAME_PROBE_TIMEOUT_MILLIS =
            "ncpb.video.native.av1_first_frame_probe_timeout_ms";
    static final String AV1_FIRST_FRAME_PROBE_MAX_PACKETS =
            "ncpb.video.native.av1_first_frame_probe_max_packets";
    static final String SEGMENT_BASE_CACHE_MAX_ENTRIES = "ncpb.video.segment_base_cache.max_entries";
    static final String LEGACY_SEGMENT_BASE_CACHE_MAX_ENTRIES = "bili.video.segment_base_cache.max_entries";

    private Fmp4NativeVideoProperties() {
    }

    static Decoder decoder() {
        return new Decoder(
                Math.max(1, NcpbSystemProperties.intValue(MAX_PENDING_FRAMES, 8)),
                Math.max(0, NcpbSystemProperties.intValue(
                        STREAM_RECOVERY_ATTEMPTS, LEGACY_STREAM_RECOVERY_ATTEMPTS, 3)),
                NcpbSystemProperties.booleanValue(REUSE_OUTPUT_BUFFERS, true),
                NcpbSystemProperties.booleanValue(DIRECT_NV12_BUFFERS, true),
                Math.max(1, NcpbSystemProperties.intValue(
                        SEGMENT_BASE_CACHE_MAX_ENTRIES, LEGACY_SEGMENT_BASE_CACHE_MAX_ENTRIES, 512)));
    }

    static Seek seek() {
        return new Seek(
                Math.max(1, NcpbSystemProperties.intValue(INIT_PROBE_BYTES, 4 * 1024 * 1024)),
                Math.max(1, NcpbSystemProperties.intValue(MOOF_SCAN_BYTES, 8 * 1024 * 1024)),
                Math.max(1, NcpbSystemProperties.intValue(SEEK_MAX_ATTEMPTS, 12)),
                Math.max(0L, NcpbSystemProperties.longValue(SEEK_PREROLL_BYTES, 1024L * 1024L)),
                Math.max(0.0D, NcpbSystemProperties.doubleValue(CLOSE_FRAGMENT_SECONDS, 3.0D)),
                Math.max(0.0D, NcpbSystemProperties.doubleValue(TARGET_EPSILON_SECONDS, 0.25D)),
                NcpbSystemProperties.doubleValue(SEEK_LEAD_SECONDS, 0.0D),
                NcpbSystemProperties.booleanValue(RANGE_SEEK_ENABLED, false),
                NcpbSystemProperties.longValue(
                        RANGE_SEEK_AUTO_OFFSET_MILLIS, LEGACY_RANGE_SEEK_AUTO_OFFSET_MILLIS, 5_000L),
                NcpbSystemProperties.doubleValue(FALLBACK_MAX_RESIDUAL_SECONDS, -1.0D),
                Math.max(0L, NcpbSystemProperties.longValue(
                        NO_COPY_DROP_GUARD_MILLIS, LEGACY_NO_COPY_DROP_GUARD_MILLIS, 1_000L)));
    }

    static FirstFrameProbe firstFrameProbe() {
        return new FirstFrameProbe(
                clamp(NcpbSystemProperties.longValue(AV1_FIRST_FRAME_PROBE_TIMEOUT_MILLIS, 2_000L),
                        250L, 10_000L),
                clamp(NcpbSystemProperties.intValue(AV1_FIRST_FRAME_PROBE_MAX_PACKETS, 256),
                        1, 4_096));
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    record Decoder(int maxPendingFrames, int streamRecoveryAttempts, boolean reuseOutputBuffers,
            boolean directNv12Buffers, int segmentBaseCacheMaxEntries) {
    }

    record Seek(int initProbeBytes, int moofScanBytes, int maxAttempts, long prerollBytes,
            double closeFragmentSeconds, double targetEpsilonSeconds, double leadSeconds, boolean rangeEnabled,
            long autoOffsetMillis, double fallbackMaxResidualSeconds, long noCopyDropGuardMillis) {
    }

    record FirstFrameProbe(long timeoutMillis, int maxPackets) {
    }
}
