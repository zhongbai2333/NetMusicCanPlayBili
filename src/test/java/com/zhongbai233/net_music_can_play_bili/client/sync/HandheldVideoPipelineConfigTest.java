package com.zhongbai233.net_music_can_play_bili.client.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandheldVideoPipelineConfigTest {
    private static final String PREFIX = "ncpb.test.handheld.video";
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        HandheldVideoPipelineConfig config = HandheldVideoPipelineConfig.fromSystemProperties(PREFIX);

        assertEquals(8_192, config.maxAllowedWidth());
        assertEquals(4_320, config.maxAllowedHeight());
        assertEquals(1_920, config.highResWarningWidth());
        assertEquals(1_080, config.highResWarningHeight());
        assertEquals(1_000_000, config.maxFrames());
        assertEquals(8L, config.frameWaitSliceMillis());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(250L), config.maxLateFrameNanos());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(750L), config.startupDropLagNanos());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(350L), config.maxDecodeLeadNanos());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(24L), config.earlyToleranceNanos());
        assertEquals(4, config.frameQueueCapacity());
        assertTrue(config.offscreenPauseDecode());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(500L), config.offscreenGraceNanos());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1_500L), config.offscreenResumeRestartLagNanos());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1_000L), config.rgbaConsumerGraceNanos());
    }

    @Test
    void unsafeValuesAreClampedAndLargeDurationsSaturate() {
        set("max_frames", "0");
        set("frame_wait_slice_ms", "0");
        set("max_late_frame_ms", "-1");
        set("startup_drop_lag_ms", "-1");
        set("max_decode_lead_ms", "-1");
        set("early_tolerance_ms", "-1");
        set("queue_capacity", "0");
        set("offscreen.grace_ms", "-1");
        set("offscreen.resume_restart_lag_ms", "-1");
        set("rgba_consumer_grace_ms", Long.toString(Long.MAX_VALUE));

        HandheldVideoPipelineConfig config = HandheldVideoPipelineConfig.fromSystemProperties(PREFIX);
        assertEquals(1, config.maxFrames());
        assertEquals(1L, config.frameWaitSliceMillis());
        assertEquals(0L, config.maxLateFrameNanos());
        assertEquals(0L, config.startupDropLagNanos());
        assertEquals(0L, config.maxDecodeLeadNanos());
        assertEquals(0L, config.earlyToleranceNanos());
        assertEquals(1, config.frameQueueCapacity());
        assertEquals(0L, config.offscreenGraceNanos());
        assertEquals(0L, config.offscreenResumeRestartLagNanos());
        assertEquals(Long.MAX_VALUE, config.rgbaConsumerGraceNanos());
    }

    private void set(String suffix, String value) {
        String key = PREFIX + "." + suffix;
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
