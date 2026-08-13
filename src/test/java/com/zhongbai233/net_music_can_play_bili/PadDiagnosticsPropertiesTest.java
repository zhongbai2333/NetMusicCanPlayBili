package com.zhongbai233.net_music_can_play_bili;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadDiagnosticsPropertiesTest {
    @AfterEach
    void clearProperties() {
        System.clearProperty(PadDiagnosticsProperties.VIDEO_DEBUG_LOG);
        System.clearProperty(PadDiagnosticsProperties.MAP_SERVER_SELF_TEST);
    }

    @Test
    void diagnosticsRemainDisabledByDefault() {
        assertFalse(PadDiagnosticsProperties.videoDebugLogEnabled());
        assertFalse(PadDiagnosticsProperties.mapServerSelfTestEnabled());
    }

    @Test
    void explicitTrueEnablesEachDiagnosticIndependently() {
        System.setProperty(PadDiagnosticsProperties.VIDEO_DEBUG_LOG, "true");
        assertTrue(PadDiagnosticsProperties.videoDebugLogEnabled());
        assertFalse(PadDiagnosticsProperties.mapServerSelfTestEnabled());

        System.setProperty(PadDiagnosticsProperties.MAP_SERVER_SELF_TEST, "TRUE");
        assertTrue(PadDiagnosticsProperties.mapServerSelfTestEnabled());
    }

    @Test
    void invalidValuesFallBackToDisabled() {
        System.setProperty(PadDiagnosticsProperties.VIDEO_DEBUG_LOG, "yes");
        System.setProperty(PadDiagnosticsProperties.MAP_SERVER_SELF_TEST, "1");
        assertFalse(PadDiagnosticsProperties.videoDebugLogEnabled());
        assertFalse(PadDiagnosticsProperties.mapServerSelfTestEnabled());
    }
}
