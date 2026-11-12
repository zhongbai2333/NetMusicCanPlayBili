package com.zhongbai233.net_music_can_play_bili;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PadDiagnosticsPropertiesTest {
    @BeforeEach
    @AfterEach
    void clearProperties() {
        System.clearProperty("ncpb.pad.video.debug_log");
        System.clearProperty("ncpb.pad.map.server_self_test");
    }

    @Test
    void diagnosticsRemainDisabledByDefault() {
        assertFalse(PadDiagnosticsProperties.videoDebugLogEnabled());
        assertFalse(PadDiagnosticsProperties.mapServerSelfTestEnabled());
    }

}
