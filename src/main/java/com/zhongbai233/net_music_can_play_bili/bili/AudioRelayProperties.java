package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

/** Shared JVM property boundary for stereo and Dolby relay behavior. */
final class AudioRelayProperties {
    static final String MUTE_MAIN_WHEN_CONNECTED = "ncpb.bili.audio.relay.mute_main_when_connected";
    static final String LEGACY_MUTE_MAIN_WHEN_STARTED = "ncpb.bili.audio.relay.mute_main_when_started";

    private AudioRelayProperties() {
    }

    static boolean muteMainWhenConnected() {
        return NcpbSystemProperties.booleanValue(
                MUTE_MAIN_WHEN_CONNECTED, LEGACY_MUTE_MAIN_WHEN_STARTED, true);
    }
}
