package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

import java.util.concurrent.TimeUnit;

/** 手持视频解码和帧节奏管线参数。 */
public record HandheldVideoPipelineConfig(
        int maxAllowedWidth,
        int maxAllowedHeight,
        int highResWarningWidth,
        int highResWarningHeight,
        int maxFrames,
        long frameWaitSliceMillis,
        long maxLateFrameNanos,
        long startupDropLagNanos,
        long maxDecodeLeadNanos,
        long earlyToleranceNanos,
        int frameQueueCapacity,
        boolean offscreenPauseDecode,
        long offscreenGraceNanos,
        long offscreenResumeRestartLagNanos,
        long rgbaConsumerGraceNanos) {
    public static HandheldVideoPipelineConfig fromSystemProperties(String prefix) {
        String keyPrefix = prefix == null || prefix.isBlank() ? "ncpb.handheld.video" : prefix.trim();
        return new HandheldVideoPipelineConfig(
                8192,
                4320,
                1920,
                1080,
                NcpbSystemProperties.intValue(keyPrefix + ".max_frames", 1_000_000),
                NcpbSystemProperties.longValue(keyPrefix + ".frame_wait_slice_ms", 8L),
                millisProperty(keyPrefix + ".max_late_frame_ms", 250L),
                millisProperty(keyPrefix + ".startup_drop_lag_ms", 750L),
                millisProperty(keyPrefix + ".max_decode_lead_ms", 350L),
                millisProperty(keyPrefix + ".early_tolerance_ms", 24L),
                NcpbSystemProperties.intValue(keyPrefix + ".queue_capacity", 4),
                NcpbSystemProperties.booleanValue(keyPrefix + ".offscreen.pause_decode", true),
                millisProperty(keyPrefix + ".offscreen.grace_ms", 500L),
                millisProperty(keyPrefix + ".offscreen.resume_restart_lag_ms", 1_500L),
                millisProperty(keyPrefix + ".rgba_consumer_grace_ms", 1_000L));
    }

    public HandheldVideoPipelineConfig {
        maxFrames = Math.max(1, maxFrames);
        frameWaitSliceMillis = Math.max(1L, frameWaitSliceMillis);
        maxLateFrameNanos = Math.max(0L, maxLateFrameNanos);
        startupDropLagNanos = Math.max(0L, startupDropLagNanos);
        maxDecodeLeadNanos = Math.max(0L, maxDecodeLeadNanos);
        earlyToleranceNanos = Math.max(0L, earlyToleranceNanos);
        frameQueueCapacity = Math.max(1, frameQueueCapacity);
        offscreenGraceNanos = Math.max(0L, offscreenGraceNanos);
        offscreenResumeRestartLagNanos = Math.max(0L, offscreenResumeRestartLagNanos);
        rgbaConsumerGraceNanos = Math.max(0L, rgbaConsumerGraceNanos);
    }

    private static long millisProperty(String name, long fallbackMillis) {
        long millis = Math.max(0L, NcpbSystemProperties.longValue(name, fallbackMillis));
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
