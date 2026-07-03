package com.zhongbai233.net_music_can_play_bili.client.sync;

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
        String keyPrefix = prefix == null || prefix.isBlank() ? "ncpb.handheld.video" : prefix;
        return new HandheldVideoPipelineConfig(
                8192,
                4320,
                1920,
                1080,
                Integer.getInteger(keyPrefix + ".max_frames", 1_000_000),
                Long.getLong(keyPrefix + ".frame_wait_slice_ms", 8L),
                millisProperty(keyPrefix + ".max_late_frame_ms", 250L),
                millisProperty(keyPrefix + ".startup_drop_lag_ms", 750L),
                millisProperty(keyPrefix + ".max_decode_lead_ms", 350L),
                millisProperty(keyPrefix + ".early_tolerance_ms", 24L),
                Integer.getInteger(keyPrefix + ".queue_capacity", 4),
                Boolean.parseBoolean(System.getProperty(keyPrefix + ".offscreen.pause_decode", "true")),
                millisProperty(keyPrefix + ".offscreen.grace_ms", 500L),
                millisProperty(keyPrefix + ".offscreen.resume_restart_lag_ms", 1_500L),
                millisProperty(keyPrefix + ".rgba_consumer_grace_ms", 1_000L));
    }

    private static long millisProperty(String name, long fallbackMillis) {
        return Long.getLong(name, fallbackMillis) * 1_000_000L;
    }
}
