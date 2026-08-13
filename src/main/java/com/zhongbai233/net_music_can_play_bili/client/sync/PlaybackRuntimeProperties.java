package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

/** JVM property boundary for playback pacing, watchdogs, and drift diagnostics. */
public final class PlaybackRuntimeProperties {
    static final String LIVE_WATCHDOG_STALL_MILLIS = "ncpb.bili.live.watchdog.stall_ms";
    static final String AUDIO_WATCHDOG_STARTUP_STALL_MILLIS =
            "ncpb.bili.audio.watchdog.startup_stall_ms";
    static final String AUDIO_WATCHDOG_NO_PROGRESS_MILLIS =
            "ncpb.bili.audio.watchdog.no_progress_ms";
    static final String AUDIO_WATCHDOG_END_GRACE_MILLIS =
            "ncpb.bili.audio.watchdog.end_grace_ms";
    static final String AUDIO_SYNC_AHEAD_TOLERANCE_TICKS =
            "ncpb.bili.audio.openal.ahead_tolerance_ticks";
    static final String LEGACY_AUDIO_SYNC_AHEAD_TOLERANCE_TICKS =
            "bili.audio.openal.ahead_tolerance_ticks";
    static final String WARN_DRIFT_MILLIS = "ncpb.playback.diagnostics.warn_drift_ms";
    static final String DEBUG_AV_DRIFT_MILLIS =
            "ncpb.playback.diagnostics.debug_av_drift_ms";
    static final String LEGACY_DEBUG_AV_DRIFT_MILLIS =
            "bili.playback.diagnostics.debug_av_drift_ms";

    private PlaybackRuntimeProperties() {
    }

    public static Watchdog watchdog() {
        return new Watchdog(
                millisToTicks(NcpbSystemProperties.intValue(LIVE_WATCHDOG_STALL_MILLIS, 45_000)),
                millisToTicks(NcpbSystemProperties.intValue(AUDIO_WATCHDOG_STARTUP_STALL_MILLIS, 15_000)),
                millisToTicks(NcpbSystemProperties.intValue(AUDIO_WATCHDOG_NO_PROGRESS_MILLIS, 12_000)),
                NcpbSystemProperties.longValue(AUDIO_WATCHDOG_END_GRACE_MILLIS, 2_000L));
    }

    public static long audioSyncAheadToleranceTicks() {
        return NcpbSystemProperties.longValue(AUDIO_SYNC_AHEAD_TOLERANCE_TICKS,
                LEGACY_AUDIO_SYNC_AHEAD_TOLERANCE_TICKS, 0L);
    }

    public static Diagnostics diagnostics() {
        return new Diagnostics(
                NcpbSystemProperties.longValue(WARN_DRIFT_MILLIS, 2_000L),
                NcpbSystemProperties.longValue(
                        DEBUG_AV_DRIFT_MILLIS, LEGACY_DEBUG_AV_DRIFT_MILLIS, 250L));
    }

    private static int millisToTicks(int millis) {
        return millis / 50;
    }

    public record Watchdog(int liveStallTicks, int audioStartupStallTicks,
            int audioNoProgressTicks, long audioEndGraceMillis) {
        public Watchdog {
            liveStallTicks = Math.max(100, liveStallTicks);
            audioStartupStallTicks = Math.max(20, audioStartupStallTicks);
            audioNoProgressTicks = Math.max(20, audioNoProgressTicks);
            audioEndGraceMillis = Math.max(0L, audioEndGraceMillis);
        }
    }

    public record Diagnostics(long warnDriftMillis, long debugAvDriftMillis) {
        public Diagnostics {
            warnDriftMillis = Math.max(0L, warnDriftMillis);
            debugAvDriftMillis = Math.max(0L, debugAvDriftMillis);
        }
    }
}
