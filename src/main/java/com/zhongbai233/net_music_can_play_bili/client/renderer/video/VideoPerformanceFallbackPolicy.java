package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/**
 * Pure decision boundary for the sustained video performance budget.
 *
 * <p>The collector deliberately lives elsewhere: this class only decides whether
 * one AV1 session is allowed to lock itself to an already-resolved H.264
 * candidate. Keeping the thresholds here prevents renderers and decoder loops
 * from acquiring subtly different fallback semantics.</p>
 */
public final class VideoPerformanceFallbackPolicy {
    public static final Config DEFAULT = new Config(5_000L, 0.80D, 3, 250L);

    private VideoPerformanceFallbackPolicy() {
    }

    public static Decision decide(Snapshot snapshot, boolean av1Candidate,
            boolean h264CandidateAvailable, boolean fallbackAlreadyLocked) {
        return decide(snapshot, av1Candidate, h264CandidateAvailable, fallbackAlreadyLocked, DEFAULT);
    }

    static Decision decide(Snapshot snapshot, boolean av1Candidate,
            boolean h264CandidateAvailable, boolean fallbackAlreadyLocked, Config config) {
        if (snapshot == null || config == null || !av1Candidate || fallbackAlreadyLocked) {
            return Decision.KEEP;
        }
        if (snapshot.observationMillis() < config.warmupMillis()) {
            return Decision.WARMING_UP;
        }
        double minimumFps = Math.max(1, snapshot.targetFps()) * config.minimumFpsRatio();
        boolean lowFps = snapshot.actualDecodeFps() + 1.0e-9D < minimumFps;
        long driftThreshold = Math.max(config.minimumDriftGrowthMillis(),
                Math.round(8_000.0D / Math.max(1, snapshot.targetFps())));
        boolean growingDrift = snapshot.consecutiveDriftGrowthSamples() >= config.driftGrowthSamples()
                && snapshot.syncDriftGrowthMillis() >= driftThreshold;
        if (!lowFps && !growingDrift) {
            return Decision.KEEP;
        }
        if (!h264CandidateAvailable) {
            return Decision.KEEP_NO_H264;
        }
        if (lowFps) {
            return Decision.FALLBACK_LOW_FPS;
        }
        if (growingDrift) {
            return Decision.FALLBACK_GROWING_DRIFT;
        }
        return Decision.KEEP;
    }

    public enum Decision {
        WARMING_UP(false, "warming-up"),
        KEEP(false, "within-budget"),
        KEEP_NO_H264(false, "no-h264-candidate"),
        FALLBACK_LOW_FPS(true, "performance-low-fps"),
        FALLBACK_GROWING_DRIFT(true, "performance-growing-av-drift");

        private final boolean fallback;
        private final String reason;

        Decision(boolean fallback, String reason) {
            this.fallback = fallback;
            this.reason = reason;
        }

        public boolean shouldFallback() {
            return fallback;
        }

        public String reason() {
            return reason;
        }
    }

    public record Config(long warmupMillis, double minimumFpsRatio,
            int driftGrowthSamples, long minimumDriftGrowthMillis) {
        public Config {
            warmupMillis = Math.max(1L, warmupMillis);
            minimumFpsRatio = Math.max(0.05D, Math.min(1.0D, minimumFpsRatio));
            driftGrowthSamples = Math.max(1, driftGrowthSamples);
            minimumDriftGrowthMillis = Math.max(1L, minimumDriftGrowthMillis);
        }
    }

    /** Immutable evidence used both by the decision and the diagnostics log. */
    public record Snapshot(long observationMillis, int targetFps, long decodedFrames,
            double actualDecodeFps, double averageDecodeMillis, double p95DecodeMillis,
            long starvationCount, long droppedFrames, double droppedFrameRatio,
            long latestSyncDriftMillis, long syncDriftGrowthMillis,
            int consecutiveDriftGrowthSamples, String backend,
            long nativeFrameBytesPeak, long nativeSurfacePeak) {
        public Snapshot {
            observationMillis = Math.max(0L, observationMillis);
            targetFps = Math.max(1, targetFps);
            decodedFrames = Math.max(0L, decodedFrames);
            actualDecodeFps = Math.max(0.0D, actualDecodeFps);
            averageDecodeMillis = Math.max(0.0D, averageDecodeMillis);
            p95DecodeMillis = Math.max(0.0D, p95DecodeMillis);
            starvationCount = Math.max(0L, starvationCount);
            droppedFrames = Math.max(0L, droppedFrames);
            droppedFrameRatio = Math.max(0.0D, Math.min(1.0D, droppedFrameRatio));
            syncDriftGrowthMillis = Math.max(0L, syncDriftGrowthMillis);
            consecutiveDriftGrowthSamples = Math.max(0, consecutiveDriftGrowthSamples);
            backend = backend == null || backend.isBlank() ? "unknown" : backend;
            nativeFrameBytesPeak = Math.max(0L, nativeFrameBytesPeak);
            nativeSurfacePeak = Math.max(0L, nativeSurfacePeak);
        }
    }
}
