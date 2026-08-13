package com.zhongbai233.net_music_can_play_bili;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

/** Shared JVM property boundary for Pad diagnostics and development-only self-tests. */
public final class PadDiagnosticsProperties {
    static final String VIDEO_DEBUG_LOG = "ncpb.pad.video.debug_log";
    static final String MAP_SERVER_SELF_TEST = "ncpb.pad.map.server_self_test";

    private PadDiagnosticsProperties() {
    }

    public static boolean videoDebugLogEnabled() {
        return NcpbSystemProperties.booleanValue(VIDEO_DEBUG_LOG, false);
    }

    public static boolean mapServerSelfTestEnabled() {
        return NcpbSystemProperties.booleanValue(MAP_SERVER_SELF_TEST, false);
    }
}
