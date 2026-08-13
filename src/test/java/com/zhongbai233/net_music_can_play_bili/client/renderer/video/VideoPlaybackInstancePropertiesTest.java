package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoPlaybackInstancePropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void timingDefaultsPreserveExistingPlaybackThresholds() {
        VideoPipelineProperties.Timing timing = VideoPipelineProperties.timing();
        assertEquals(0L, timing.audioLatencyCompensationMillis());
        assertEquals(10_000L, timing.chaseWindowMillis());
        assertEquals(2_500L, timing.slowdownWindowMillis());
        assertEquals(1_500L, timing.runtimeLagRestartMillis());
        assertEquals(1_500L, timing.runtimeLagConfirmMillis());
        assertEquals(5_000L, timing.runtimeLagRestartCooldownMillis());
        assertEquals(8_000L, timing.decoderStabilizationMillis());
        assertEquals(3_000L, timing.decoderRestartCloseTimeoutMillis());
        assertEquals(20_000L, timing.firstFrameTimeoutMillis());
        assertEquals(2, timing.firstFrameRecoveryAttempts());
    }

    @Test
    void offscreenAndPresentationDefaultsRemainStable() {
        VideoPipelineProperties.Offscreen offscreen = VideoPipelineProperties.offscreen();
        assertTrue(offscreen.pauseDecode());
        assertEquals(500L, offscreen.graceMillis());
        assertEquals(1_500L, offscreen.resumeRestartLagMillis());
        assertEquals(-0.20D, offscreen.prewarmDotThreshold());

        VideoPipelineProperties.Presentation presentation = VideoPipelineProperties.presentation();
        assertEquals(4096, presentation.maxSourceWidth());
        assertEquals(2304, presentation.maxSourceHeight());
        assertEquals(0.03D, presentation.irisWarningViewDepthOffset());
        assertEquals(-0.01F, presentation.irisWarningLocalDepthOffset());
        assertEquals(3, presentation.queueCapacity());
    }

    @Test
    void canonicalKeysOverrideExistingBiliAliases() {
        set(VideoPipelineProperties.LEGACY_AUDIO_LATENCY_COMPENSATION_MILLIS, "320");
        set(VideoPipelineProperties.LEGACY_OFFSCREEN_RESUME_RESTART_LAG_MILLIS, "2400");
        assertEquals(320L, VideoPipelineProperties.timing().audioLatencyCompensationMillis());
        assertEquals(2400L, VideoPipelineProperties.offscreen().resumeRestartLagMillis());

        set(VideoPipelineProperties.AUDIO_LATENCY_COMPENSATION_MILLIS, "160");
        set(VideoPipelineProperties.OFFSCREEN_RESUME_RESTART_LAG_MILLIS, "1200");
        assertEquals(160L, VideoPipelineProperties.timing().audioLatencyCompensationMillis());
        assertEquals(1200L, VideoPipelineProperties.offscreen().resumeRestartLagMillis());
    }

    @Test
    void invalidAndNonFiniteValuesUseCompatibilityDefaults() {
        set(VideoPipelineProperties.CHASE_WINDOW_MILLIS, "invalid");
        set(VideoPipelineProperties.OFFSCREEN_PAUSE_DECODE, "yes");
        set(VideoPipelineProperties.OFFSCREEN_PREWARM_DOT_THRESHOLD, "NaN");
        set(VideoPipelineProperties.IRIS_WARNING_VIEW_DEPTH_OFFSET, "Infinity");
        set(VideoPipelineProperties.IRIS_WARNING_LOCAL_DEPTH_OFFSET, "NaN");
        set(VideoPipelineProperties.QUEUE_CAPACITY, "invalid");

        assertEquals(10_000L, VideoPipelineProperties.timing().chaseWindowMillis());
        assertTrue(VideoPipelineProperties.offscreen().pauseDecode());
        assertEquals(-0.20D, VideoPipelineProperties.offscreen().prewarmDotThreshold());
        assertEquals(0.03D, VideoPipelineProperties.presentation().irisWarningViewDepthOffset());
        assertEquals(-0.01F, VideoPipelineProperties.presentation().irisWarningLocalDepthOffset());
        assertEquals(3, VideoPipelineProperties.presentation().queueCapacity());
    }

    @Test
    void runtimeThresholdsRemainReadableAtEachCall() {
        assertEquals(750L, VideoPipelineProperties.startupDropLagMillis());
        assertEquals(250L, VideoPipelineProperties.maxDecodeLeadMillis());
        assertEquals(1_000L, VideoPipelineProperties.uploadPumpWarnMillis());
        assertEquals(12L, VideoPipelineProperties.earlyToleranceMillis());
        assertEquals(250L, VideoPipelineProperties.maxVisibleLagMillis());
        assertEquals(2, VideoPipelineProperties.startupPrebufferFrames());
        assertEquals(250L, VideoPipelineProperties.startupPrebufferMaxWaitMillis());
        assertTrue(VideoPipelineProperties.loadingPlaceholderEnabled());

        set(VideoPipelineProperties.STARTUP_DROP_LAG_MILLIS, "900");
        set(VideoPipelineProperties.MAX_DECODE_LEAD_MILLIS, "300");
        set(VideoPipelineProperties.UPLOAD_PUMP_WARN_MILLIS, "1200");
        set(VideoPipelineProperties.EARLY_TOLERANCE_MILLIS, "18");
        set(VideoPipelineProperties.MAX_VISIBLE_LAG_MILLIS, "400");
        set(VideoPipelineProperties.STARTUP_PREBUFFER_FRAMES, "4");
        set(VideoPipelineProperties.STARTUP_PREBUFFER_MAX_WAIT_MILLIS, "350");
        set(VideoPipelineProperties.LOADING_PLACEHOLDER, "false");

        assertEquals(900L, VideoPipelineProperties.startupDropLagMillis());
        assertEquals(300L, VideoPipelineProperties.maxDecodeLeadMillis());
        assertEquals(1200L, VideoPipelineProperties.uploadPumpWarnMillis());
        assertEquals(18L, VideoPipelineProperties.earlyToleranceMillis());
        assertEquals(400L, VideoPipelineProperties.maxVisibleLagMillis());
        assertEquals(4, VideoPipelineProperties.startupPrebufferFrames());
        assertEquals(350L, VideoPipelineProperties.startupPrebufferMaxWaitMillis());
        assertFalse(VideoPipelineProperties.loadingPlaceholderEnabled());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
