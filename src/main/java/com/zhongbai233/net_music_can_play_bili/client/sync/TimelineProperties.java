package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

import java.util.concurrent.TimeUnit;

/** JVM property boundary for shared, turntable, and handheld client timelines. */
final class TimelineProperties {
    static final String HARD_SYNC_MILLIS = "ncpb.media.timeline.hard_sync_ms";
    static final String LEGACY_HARD_SYNC_MILLIS = "bili.media.timeline.hard_sync_ms";
    static final String DEPRECATED_TURNTABLE_HARD_SYNC_MILLIS =
            "ncpb.turntable.timeline.hard_sync_ms";
    static final String MAX_SMOOTH_CORRECTION_MILLIS =
            "ncpb.media.timeline.max_smooth_correction_ms";
    static final String LEGACY_MAX_SMOOTH_CORRECTION_MILLIS =
            "bili.media.timeline.max_smooth_correction_ms";
    static final String DEPRECATED_TURNTABLE_MAX_SMOOTH_CORRECTION_MILLIS =
            "ncpb.turntable.timeline.max_smooth_correction_ms";
    static final String SMOOTH_CORRECTION_RATIO = "ncpb.media.timeline.smooth_correction_ratio";
    static final String LEGACY_SMOOTH_CORRECTION_RATIO =
            "ncpb.turntable.timeline.smooth_correction_ratio";

    static final String TURNTABLE_AUDIO_ANCHOR = "ncpb.turntable.timeline.audio_anchor";
    static final String TURNTABLE_AUDIO_ANCHOR_MAX_LAG_MILLIS =
            "ncpb.turntable.timeline.audio_anchor_max_lag_ms";
    static final String TURNTABLE_AUDIO_ANCHOR_MAX_LEAD_MILLIS =
            "ncpb.turntable.timeline.audio_anchor_max_lead_ms";
    static final String TURNTABLE_CLOCK_PRUNE_INTERVAL_MILLIS =
            "ncpb.turntable.timeline.clock_prune_interval_ms";
    static final String TURNTABLE_VISUAL_HARD_SYNC_MILLIS =
            "ncpb.turntable.timeline.visual_hard_sync_ms";
    static final String TURNTABLE_VISUAL_MAX_CORRECTION_MILLIS =
            "ncpb.turntable.timeline.visual_max_correction_ms";
    static final String TURNTABLE_VISUAL_CORRECTION_RATIO =
            "ncpb.turntable.timeline.visual_correction_ratio";

    static final String HANDHELD_AUDIO_ANCHOR_MAX_LAG_MILLIS =
            "ncpb.media.timeline.audio_anchor_max_lag_ms";
    static final String HANDHELD_AUDIO_ANCHOR_MAX_LEAD_MILLIS =
            "ncpb.media.timeline.audio_anchor_max_lead_ms";

    private TimelineProperties() {
    }

    static Clock clock() {
        long deprecatedHardSync = NcpbSystemProperties.longValue(
                DEPRECATED_TURNTABLE_HARD_SYNC_MILLIS, 1_500L);
        long deprecatedMaxCorrection = NcpbSystemProperties.longValue(
                DEPRECATED_TURNTABLE_MAX_SMOOTH_CORRECTION_MILLIS, 80L);
        return new Clock(
                NcpbSystemProperties.longValue(
                        HARD_SYNC_MILLIS, LEGACY_HARD_SYNC_MILLIS, deprecatedHardSync),
                NcpbSystemProperties.longValue(
                        MAX_SMOOTH_CORRECTION_MILLIS, LEGACY_MAX_SMOOTH_CORRECTION_MILLIS,
                        deprecatedMaxCorrection),
                NcpbSystemProperties.doubleValue(
                        SMOOTH_CORRECTION_RATIO, LEGACY_SMOOTH_CORRECTION_RATIO, 0.12D));
    }

    static Turntable turntable() {
        long pruneMillis = Math.max(1_000L,
                NcpbSystemProperties.longValue(TURNTABLE_CLOCK_PRUNE_INTERVAL_MILLIS, 30_000L));
        return new Turntable(
                NcpbSystemProperties.booleanValue(TURNTABLE_AUDIO_ANCHOR, true),
                NcpbSystemProperties.longValue(TURNTABLE_AUDIO_ANCHOR_MAX_LAG_MILLIS, 2_000L),
                NcpbSystemProperties.longValue(TURNTABLE_AUDIO_ANCHOR_MAX_LEAD_MILLIS, 500L),
                TimeUnit.MILLISECONDS.toNanos(pruneMillis),
                NcpbSystemProperties.longValue(TURNTABLE_VISUAL_HARD_SYNC_MILLIS, 500L),
                NcpbSystemProperties.longValue(TURNTABLE_VISUAL_MAX_CORRECTION_MILLIS, 20L),
                NcpbSystemProperties.doubleValue(TURNTABLE_VISUAL_CORRECTION_RATIO, 0.20D));
    }

    static Handheld handheld() {
        return new Handheld(
                NcpbSystemProperties.longValue(HANDHELD_AUDIO_ANCHOR_MAX_LAG_MILLIS, 2_000L),
                NcpbSystemProperties.longValue(HANDHELD_AUDIO_ANCHOR_MAX_LEAD_MILLIS, 500L));
    }

    record Clock(long hardSyncThresholdMillis, long maxSmoothCorrectionMillis,
            double smoothCorrectionRatio) {
        Clock {
            hardSyncThresholdMillis = Math.max(0L, hardSyncThresholdMillis);
            maxSmoothCorrectionMillis = Math.max(0L, maxSmoothCorrectionMillis);
            smoothCorrectionRatio = clampRatio(smoothCorrectionRatio);
        }
    }

    record Turntable(boolean audioAnchored, long audioAnchorMaxLagMillis,
            long audioAnchorMaxLeadMillis, long clockPruneIntervalNanos,
            long visualHardSyncMillis, long visualMaxCorrectionMillis,
            double visualCorrectionRatio) {
        Turntable {
            audioAnchorMaxLagMillis = Math.max(0L, audioAnchorMaxLagMillis);
            audioAnchorMaxLeadMillis = Math.max(0L, audioAnchorMaxLeadMillis);
            clockPruneIntervalNanos = Math.max(1L, clockPruneIntervalNanos);
            visualHardSyncMillis = Math.max(0L, visualHardSyncMillis);
            visualMaxCorrectionMillis = Math.max(0L, visualMaxCorrectionMillis);
            visualCorrectionRatio = clampRatio(visualCorrectionRatio);
        }
    }

    record Handheld(long audioAnchorMaxLagMillis, long audioAnchorMaxLeadMillis) {
        Handheld {
            audioAnchorMaxLagMillis = Math.max(0L, audioAnchorMaxLagMillis);
            audioAnchorMaxLeadMillis = Math.max(0L, audioAnchorMaxLeadMillis);
        }
    }

    private static double clampRatio(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
