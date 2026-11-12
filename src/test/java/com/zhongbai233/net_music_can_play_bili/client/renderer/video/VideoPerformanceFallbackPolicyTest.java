package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoPerformanceFallbackPolicyTest {
    private static final VideoPerformanceFallbackPolicy.Config CONFIG =
            new VideoPerformanceFallbackPolicy.Config(5_000L, 0.80D, 3, 250L);

    @Test
    void sustainedDecodeRateBelowEightyPercentFallsBack() {
        assertEquals(VideoPerformanceFallbackPolicy.Decision.FALLBACK_LOW_FPS,
                decide(snapshot(5_000L, 23.99D, 0L, 0), true, true, false));
    }

    @Test
    void growingDriftFallsBackAfterConsecutiveEvidence() {
        assertEquals(VideoPerformanceFallbackPolicy.Decision.FALLBACK_GROWING_DRIFT,
                decide(snapshot(5_000L, 30.0D, 300L, 3), true, true, false));
        assertEquals(VideoPerformanceFallbackPolicy.Decision.KEEP,
                decide(snapshot(5_000L, 30.0D, 300L, 2), true, true, false));
    }

    @Test
    void neverFallsBackFromH264OrWithoutResolvedH264Candidate() {
        assertEquals(VideoPerformanceFallbackPolicy.Decision.KEEP,
                decide(snapshot(5_000L, 1.0D, 999L, 9), false, true, false));
        assertEquals(VideoPerformanceFallbackPolicy.Decision.KEEP_NO_H264,
                decide(snapshot(5_000L, 1.0D, 999L, 9), true, false, false));
        assertEquals(VideoPerformanceFallbackPolicy.Decision.KEEP,
                decide(snapshot(5_000L, 30.0D, 0L, 0), true, false, false));
    }

    @Test
    void aLockedSessionCannotOscillateBackToAv1() {
        assertEquals(VideoPerformanceFallbackPolicy.Decision.KEEP,
                decide(snapshot(5_000L, 1.0D, 999L, 9), true, true, true));
    }

    @Test
    void driftUsesAbsoluteDifferenceSoCatchingUpFromAheadIsNotGrowth() {
        VideoPerformanceMonitor monitor = new VideoPerformanceMonitor();
        monitor.start(0L, 30, "vaapi");
        monitor.recordSyncDriftMillis(-400L);
        monitor.recordSyncDriftMillis(-300L);
        monitor.recordSyncDriftMillis(-200L);

        VideoPerformanceFallbackPolicy.Snapshot snapshot = monitor.snapshot(5_000_000_000L);
        assertEquals(0L, snapshot.syncDriftGrowthMillis());
        assertEquals(0, snapshot.consecutiveDriftGrowthSamples());
    }
    @Test
    void offscreenSuspensionResetsDriftGrowthBeforeVisibleResume() {
        VideoPerformanceMonitor monitor = new VideoPerformanceMonitor();
        monitor.start(0L, 30, "videotoolbox");
        monitor.recordSyncDriftMillis(100L);
        monitor.recordSyncDriftMillis(200L);
        monitor.resetSyncDriftWindow();
        monitor.recordSyncDriftMillis(50L);

        VideoPerformanceFallbackPolicy.Snapshot snapshot = monitor.snapshot(5_000_000_000L);
        assertEquals(50L, snapshot.latestSyncDriftMillis());
        assertEquals(0L, snapshot.syncDriftGrowthMillis());
        assertEquals(0, snapshot.consecutiveDriftGrowthSamples());
    }

    private static VideoPerformanceFallbackPolicy.Decision decide(
            VideoPerformanceFallbackPolicy.Snapshot snapshot, boolean av1, boolean h264, boolean locked) {
        return VideoPerformanceFallbackPolicy.decide(snapshot, av1, h264, locked, CONFIG);
    }

    private static VideoPerformanceFallbackPolicy.Snapshot snapshot(long elapsedMillis, double actualFps,
            long driftGrowthMillis, int growthSamples) {
        return new VideoPerformanceFallbackPolicy.Snapshot(elapsedMillis, 30, 150L, actualFps,
                2.0D, 4.0D, 0L, 0L, 0.0D, driftGrowthMillis,
                driftGrowthMillis, growthSamples, "videotoolbox", 0L, 0L);
    }
}
