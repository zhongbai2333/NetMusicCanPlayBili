package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoPerformanceFallbackPolicyTest {
    private static final VideoPerformanceFallbackPolicy.Config CONFIG =
            new VideoPerformanceFallbackPolicy.Config(5_000L, 0.80D, 3, 250L);

    @Test
    void waitsForFullWarmupWindow() {
        assertEquals(VideoPerformanceFallbackPolicy.Decision.WARMING_UP,
                decide(snapshot(4_999L, 10.0D, 0L, 0), true, true, false));
    }

    @Test
    void exactEightyPercentRemainsWithinBudget() {
        assertEquals(VideoPerformanceFallbackPolicy.Decision.KEEP,
                decide(snapshot(5_000L, 24.0D, 0L, 0), true, true, false));
    }

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
    void monitorExcludesPausedTimeAndReportsPercentileDropsAndResourcesShape() {
        VideoPerformanceMonitor monitor = new VideoPerformanceMonitor();
        monitor.start(1_000_000_000L, 30, "cpu(libdav1d)");
        monitor.recordDecodedFrame(1_000_000L);
        monitor.recordDecodedFrame(3_000_000L);
        monitor.recordDecodedFrame(2_000_000L);
        monitor.recordDroppedFrames(1L);
        monitor.recordStarvation();
        monitor.recordSyncDriftMillis(10L);
        monitor.recordSyncDriftMillis(20L);
        monitor.pause(2_000_000_000L);
        monitor.resume(7_000_000_000L);
        VideoPerformanceFallbackPolicy.Snapshot snapshot = monitor.snapshot(8_000_000_000L);

        assertEquals(2_000L, snapshot.observationMillis());
        assertEquals(3L, snapshot.decodedFrames());
        assertEquals(2.0D, snapshot.averageDecodeMillis(), 0.0001D);
        assertEquals(3.0D, snapshot.p95DecodeMillis(), 0.0001D);
        assertEquals(1L, snapshot.starvationCount());
        assertEquals(0.25D, snapshot.droppedFrameRatio(), 0.0001D);
        assertEquals("cpu(libdav1d)", snapshot.backend());
        assertTrue(snapshot.actualDecodeFps() > 1.49D && snapshot.actualDecodeFps() < 1.51D);
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
