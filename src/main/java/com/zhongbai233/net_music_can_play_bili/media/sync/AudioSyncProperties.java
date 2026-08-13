package com.zhongbai233.net_music_can_play_bili.media.sync;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

/** JVM property boundary for shared stereo/Dolby audio synchronization thresholds. */
final class AudioSyncProperties {
    static final String CATCH_UP_START_TICKS = "ncpb.bili.audio.sync.catch_up_start_ticks";
    static final String LEGACY_CATCH_UP_START_TICKS = "bili.audio.sync.catch_up_start_ticks";
    static final String CATCH_UP_FULL_TICKS = "ncpb.bili.audio.sync.catch_up_full_ticks";
    static final String LEGACY_CATCH_UP_FULL_TICKS = "bili.audio.sync.catch_up_full_ticks";
    static final String OUTPUT_LAG_FLUSH_TICKS = "ncpb.bili.audio.timeline.flush_output_lag_ticks";
    static final String LEGACY_OUTPUT_LAG_FLUSH_TICKS = "bili.audio.timeline.flush_output_lag_ticks";
    static final String FED_NEAR_TARGET_TICKS =
            "ncpb.bili.audio.timeline.output_lag_fed_near_target_ticks";
    static final String LEGACY_FED_NEAR_TARGET_TICKS =
            "bili.audio.timeline.output_lag_fed_near_target_ticks";
    static final String FLUSH_AHEAD_TICKS = "ncpb.bili.audio.timeline.flush_ahead_ticks";

    private AudioSyncProperties() {
    }

    static AudioSyncPolicy policy() {
        return new AudioSyncPolicy(
                NcpbSystemProperties.longValue(CATCH_UP_START_TICKS, LEGACY_CATCH_UP_START_TICKS, 8L),
                NcpbSystemProperties.longValue(CATCH_UP_FULL_TICKS, LEGACY_CATCH_UP_FULL_TICKS, 28L),
                NcpbSystemProperties.longValue(
                        OUTPUT_LAG_FLUSH_TICKS, LEGACY_OUTPUT_LAG_FLUSH_TICKS, 40L),
                NcpbSystemProperties.longValue(
                        FED_NEAR_TARGET_TICKS, LEGACY_FED_NEAR_TARGET_TICKS, 8L),
                NcpbSystemProperties.longValue(FLUSH_AHEAD_TICKS, 12L));
    }
}
