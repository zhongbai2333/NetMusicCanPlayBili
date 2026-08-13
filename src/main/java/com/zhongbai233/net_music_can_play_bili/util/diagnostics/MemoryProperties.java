package com.zhongbai233.net_music_can_play_bili.util.diagnostics;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

import java.util.concurrent.TimeUnit;

/** JVM property boundary for memory tracking, reporting, and client protection limits. */
public final class MemoryProperties {
    static final String DIAGNOSTICS_ENABLED = "ncpb.memory.diagnostics";
    static final String PROTECTION_ENABLED = "ncpb.memory.protection";
    static final String REPORT_INTERVAL_MILLIS = "ncpb.memory.report_interval_ms";
    static final String SAMPLE_INTERVAL_MILLIS = "ncpb.memory.protection.sample_interval_ms";
    static final String OWNED_NATIVE_LIMIT_MIB = "ncpb.memory.protection.owned_native_mib";
    static final String GPU_PBO_LIMIT_MIB = "ncpb.memory.protection.gpu_pbo_mib";
    static final String FFMPEG_LIMIT_MIB = "ncpb.memory.protection.ffmpeg_mib";
    static final String D3D11_LOGICAL_LIMIT_MIB = "ncpb.memory.protection.d3d11_logical_mib";
    static final String D3D11_SURFACE_LIMIT = "ncpb.memory.protection.d3d11_surfaces";
    static final String CONSECUTIVE_SAMPLES = "ncpb.memory.protection.consecutive_samples";
    static final String COOLDOWN_MILLIS = "ncpb.memory.protection.cooldown_ms";
    static final String RECOVERY_RATIO = "ncpb.memory.protection.recovery_ratio";

    private static final long MIB = 1_048_576L;

    private MemoryProperties() {
    }

    public static Flags flags() {
        return new Flags(
                NcpbSystemProperties.booleanValue(DIAGNOSTICS_ENABLED, false),
                NcpbSystemProperties.booleanValue(PROTECTION_ENABLED, true));
    }

    public static long reportIntervalNanos() {
        return durationNanos(REPORT_INTERVAL_MILLIS, 5_000L, 1_000L);
    }

    public static Protection protection() {
        return new Protection(
                NcpbSystemProperties.booleanValue(PROTECTION_ENABLED, true),
                durationNanos(SAMPLE_INTERVAL_MILLIS, 2_000L, 500L),
                mibToBytes(NcpbSystemProperties.longValue(OWNED_NATIVE_LIMIT_MIB, 512L)),
                mibToBytes(NcpbSystemProperties.longValue(GPU_PBO_LIMIT_MIB, 512L)),
                mibToBytes(NcpbSystemProperties.longValue(FFMPEG_LIMIT_MIB, 1_024L)),
                mibToBytes(NcpbSystemProperties.longValue(D3D11_LOGICAL_LIMIT_MIB, 2_048L)),
                NcpbSystemProperties.longValue(D3D11_SURFACE_LIMIT, 256L),
                NcpbSystemProperties.intValue(CONSECUTIVE_SAMPLES, 15),
                durationNanos(COOLDOWN_MILLIS, 60_000L, 5_000L),
                NcpbSystemProperties.doubleValue(RECOVERY_RATIO, 0.65D));
    }

    private static long durationNanos(String key, long fallbackMillis, long minimumMillis) {
        long millis = Math.max(minimumMillis, NcpbSystemProperties.longValue(key, fallbackMillis));
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private static long mibToBytes(long mib) {
        long safeMib = Math.max(0L, mib);
        return safeMib > Long.MAX_VALUE / MIB ? Long.MAX_VALUE : safeMib * MIB;
    }

    public record Flags(boolean diagnosticsEnabled, boolean protectionEnabled) {
    }

    public record Protection(boolean enabled, long sampleIntervalNanos,
            long ownedNativeBytes, long gpuPboBytes, long ffmpegBytes,
            long d3d11LogicalBytes, long d3d11Surfaces, int consecutiveSamples,
            long cooldownNanos, double recoveryRatio) {
        public Protection {
            sampleIntervalNanos = Math.max(1L, sampleIntervalNanos);
            ownedNativeBytes = Math.max(0L, ownedNativeBytes);
            gpuPboBytes = Math.max(0L, gpuPboBytes);
            ffmpegBytes = Math.max(0L, ffmpegBytes);
            d3d11LogicalBytes = Math.max(0L, d3d11LogicalBytes);
            consecutiveSamples = Math.max(1, consecutiveSamples);
            cooldownNanos = Math.max(0L, cooldownNanos);
            recoveryRatio = Math.max(0.05D, Math.min(0.95D, recoveryRatio));
        }
    }
}
