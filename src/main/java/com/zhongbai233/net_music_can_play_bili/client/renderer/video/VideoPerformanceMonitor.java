package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder;

import java.util.Arrays;

/** Thread-safe per-candidate collector for the five-second performance budget. */
public final class VideoPerformanceMonitor {
    private static final int MAX_DECODE_SAMPLES = 512;
    private static final long RESOURCE_SAMPLE_INTERVAL_NANOS = 500_000_000L;

    private final long[] decodeSamples = new long[MAX_DECODE_SAMPLES];
    private int decodeSampleCount;
    private int decodeSampleCursor;
    private long decodeNanosTotal;
    private long decodedFrames;
    private long starvationCount;
    private long droppedFrames;
    private long startedNanos;
    private long suspendedStartedNanos;
    private long suspendedNanos;
    private long lastResourceSampleNanos;
    private long nativeFrameBytesPeak;
    private long nativeSurfacePeak;
    private long firstSyncDriftMagnitudeMillis = Long.MIN_VALUE;
    private long latestSyncDriftMillis;
    private long previousSyncDriftMagnitudeMillis = Long.MIN_VALUE;
    private int consecutiveDriftGrowthSamples;
    private long observationEpoch;
    private int targetFps = 1;
    private String backend = "unknown";
    private boolean started;

    public synchronized void start(long nowNanos, int targetFps, String backend) {
        Arrays.fill(decodeSamples, 0L);
        decodeSampleCount = 0;
        decodeSampleCursor = 0;
        decodeNanosTotal = 0L;
        decodedFrames = 0L;
        starvationCount = 0L;
        droppedFrames = 0L;
        startedNanos = nowNanos;
        suspendedStartedNanos = 0L;
        suspendedNanos = 0L;
        lastResourceSampleNanos = 0L;
        nativeFrameBytesPeak = 0L;
        nativeSurfacePeak = 0L;
        firstSyncDriftMagnitudeMillis = Long.MIN_VALUE;
        latestSyncDriftMillis = 0L;
        previousSyncDriftMagnitudeMillis = Long.MIN_VALUE;
        consecutiveDriftGrowthSamples = 0;
        this.targetFps = Math.max(1, targetFps);
        this.backend = backend == null || backend.isBlank() ? "unknown" : backend;
        observationEpoch++;
        started = true;
    }

    public synchronized void pause(long nowNanos) {
        if (started && suspendedStartedNanos == 0L) {
            suspendedStartedNanos = Math.max(startedNanos, nowNanos);
        }
    }

    public synchronized void resume(long nowNanos) {
        if (started && suspendedStartedNanos != 0L) {
            suspendedNanos += Math.max(0L, nowNanos - suspendedStartedNanos);
            suspendedStartedNanos = 0L;
        }
    }

    public synchronized void recordDecodedFrame(long decodeNanos) {
        if (!started) {
            return;
        }
        long safe = Math.max(0L, decodeNanos);
        decodedFrames++;
        decodeNanosTotal += safe;
        decodeSamples[decodeSampleCursor] = safe;
        decodeSampleCursor = (decodeSampleCursor + 1) % decodeSamples.length;
        decodeSampleCount = Math.min(decodeSamples.length, decodeSampleCount + 1);
    }

    public synchronized void recordStarvation() {
        if (started) {
            starvationCount++;
        }
    }

    public synchronized void recordDroppedFrames(long count) {
        if (started && count > 0L) {
            droppedFrames += count;
        }
    }

    public synchronized void recordSyncDriftMillis(long driftMillis) {
        if (!started) {
            return;
        }
        long magnitude = driftMillis == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(driftMillis);
        latestSyncDriftMillis = driftMillis;
        if (firstSyncDriftMagnitudeMillis == Long.MIN_VALUE) {
            firstSyncDriftMagnitudeMillis = magnitude;
        }
        if (previousSyncDriftMagnitudeMillis != Long.MIN_VALUE
                && magnitude > previousSyncDriftMagnitudeMillis + 5L) {
            consecutiveDriftGrowthSamples++;
        } else if (previousSyncDriftMagnitudeMillis != Long.MIN_VALUE
                && magnitude <= previousSyncDriftMagnitudeMillis) {
            consecutiveDriftGrowthSamples = 0;
        }
        previousSyncDriftMagnitudeMillis = magnitude;
    }
    public synchronized void resetSyncDriftWindow() {
        firstSyncDriftMagnitudeMillis = Long.MIN_VALUE;
        latestSyncDriftMillis = 0L;
        previousSyncDriftMagnitudeMillis = Long.MIN_VALUE;
        consecutiveDriftGrowthSamples = 0;
    }


    /** Samples process-wide native counters at most twice per second. */
    public void sampleNativeResources(long nowNanos) {
        long epoch;
        synchronized (this) {
            if (!started || (lastResourceSampleNanos != 0L
                    && nowNanos - lastResourceSampleNanos < RESOURCE_SAMPLE_INTERVAL_NANOS)) {
                return;
            }
            lastResourceSampleNanos = nowNanos;
            epoch = observationEpoch;
        }
        VideoNativeDecoder.NativeMemoryStats stats = VideoNativeDecoder.nativeMemoryStats();
        synchronized (this) {
            if (started && epoch == observationEpoch && stats.available()) {
                nativeFrameBytesPeak = Math.max(nativeFrameBytesPeak,
                        Math.max(stats.ffmpegCurrentBytes(), stats.d3d11LogicalBytesCurrent()));
                nativeSurfacePeak = Math.max(nativeSurfacePeak, stats.d3d11SurfaceCurrent());
            }
        }
    }

    public synchronized VideoPerformanceFallbackPolicy.Snapshot snapshot(long nowNanos) {
        long observationNanos = observationNanos(nowNanos);
        double seconds = observationNanos / 1_000_000_000.0D;
        double actualFps = seconds > 0.0D ? decodedFrames / seconds : 0.0D;
        double averageMillis = decodedFrames > 0L
                ? decodeNanosTotal / (double) decodedFrames / 1_000_000.0D : 0.0D;
        long[] sorted = Arrays.copyOf(decodeSamples, decodeSampleCount);
        Arrays.sort(sorted);
        double p95Millis = sorted.length == 0 ? 0.0D
                : sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95D) - 1)]
                        / 1_000_000.0D;
        long latestMagnitude = latestSyncDriftMillis == Long.MIN_VALUE
                ? Long.MAX_VALUE : Math.abs(latestSyncDriftMillis);
        long driftGrowth = firstSyncDriftMagnitudeMillis == Long.MIN_VALUE
                ? 0L : Math.max(0L, latestMagnitude - firstSyncDriftMagnitudeMillis);
        long totalOutcomes = decodedFrames + droppedFrames;
        double dropRatio = totalOutcomes > 0L ? droppedFrames / (double) totalOutcomes : 0.0D;
        return new VideoPerformanceFallbackPolicy.Snapshot(
                observationNanos / 1_000_000L, targetFps, decodedFrames, actualFps,
                averageMillis, p95Millis, starvationCount, droppedFrames, dropRatio,
                latestSyncDriftMillis, driftGrowth, consecutiveDriftGrowthSamples,
                backend, nativeFrameBytesPeak, nativeSurfacePeak);
    }

    public synchronized boolean started() {
        return started;
    }

    private long observationNanos(long nowNanos) {
        if (!started) {
            return 0L;
        }
        long pendingSuspension = suspendedStartedNanos != 0L
                ? Math.max(0L, nowNanos - suspendedStartedNanos) : 0L;
        return Math.max(0L, nowNanos - startedNanos - suspendedNanos - pendingSuspension);
    }
}
