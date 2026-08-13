package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

/** JVM property boundary for OpenAL HRTF overrides. */
final class OpenAlHrtfProperties {
    static final String FORCE_HRTF = "ncpb.dolby.force_hrtf";
    static final String LEGACY_FORCE_HRTF = "ncpb.dolby.forceHrtf";
    static final String DISABLE_HRTF = "ncpb.dolby.disable_hrtf";
    static final String LEGACY_DISABLE_HRTF = "ncpb.dolby.disableHrtf";
    static final String FORCE_HRTF_WITH_CHANNEL = "ncpb.dolby.force_hrtf_with_channel";
    static final String LEGACY_FORCE_HRTF_WITH_CHANNEL = "ncpb.dolby.forceHrtfWithChannel";

    private OpenAlHrtfProperties() {
    }

    static Settings settings() {
        boolean forceHrtf = NcpbSystemProperties.booleanValue(FORCE_HRTF, LEGACY_FORCE_HRTF, false);
        boolean disableHrtf = NcpbSystemProperties.booleanValue(DISABLE_HRTF, LEGACY_DISABLE_HRTF, false);
        boolean forceWithChannel = NcpbSystemProperties.booleanValue(
                FORCE_HRTF_WITH_CHANNEL, LEGACY_FORCE_HRTF_WITH_CHANNEL, false);
        return new Settings(forceHrtf && !disableHrtf, forceWithChannel);
    }

    record Settings(boolean forceHrtf, boolean forceHrtfWithChannel) {
    }
}
