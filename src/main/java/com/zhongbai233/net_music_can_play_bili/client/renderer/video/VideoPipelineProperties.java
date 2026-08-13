package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Shared JVM property boundary for legacy and instance-based video pipelines. */
public final class VideoPipelineProperties {
    static final String NETWORK_ERROR_PLACEHOLDER = "ncpb.video.pipeline.network_error_placeholder";
    static final String PIXEL_MODE = "ncpb.video.pixel.mode";
    static final String FAST_NATIVE_UPLOAD = "ncpb.video.fast_native_upload";
    static final String NV12_PBO = "ncpb.video.nv12.pbo";
    static final String NV12_UV_RG8 = "ncpb.video.nv12.uv_rg8";
    static final String YUV_MATRIX = "ncpb.video.yuv.matrix";
    static final String YUV_SHADER_DEBUG = "ncpb.video.yuv.shader_debug";
    static final String YUV_NO_DEPTH_WRITE = "ncpb.video.yuv.no_depth_write";
    static final String AUDIO_LATENCY_COMPENSATION_MILLIS =
            "ncpb.video.pipeline.audio_latency_compensation_ms";
    static final String LEGACY_AUDIO_LATENCY_COMPENSATION_MILLIS =
            "bili.video.pipeline.audio_latency_compensation_ms";
    static final String CHASE_WINDOW_MILLIS = "ncpb.video.pipeline.chase_window_ms";
    static final String SLOWDOWN_WINDOW_MILLIS = "ncpb.video.pipeline.slowdown_window_ms";
    static final String RUNTIME_LAG_RESTART_MILLIS = "ncpb.video.pipeline.runtime_lag_restart_ms";
    static final String RUNTIME_LAG_CONFIRM_MILLIS = "ncpb.video.pipeline.runtime_lag_confirm_ms";
    static final String RUNTIME_LAG_RESTART_COOLDOWN_MILLIS =
            "ncpb.video.pipeline.runtime_lag_restart_cooldown_ms";
    static final String DECODER_STABILIZATION_MILLIS = "ncpb.video.pipeline.decoder_stabilization_ms";
    static final String DECODER_RESTART_CLOSE_TIMEOUT_MILLIS =
            "ncpb.video.pipeline.decoder_restart_close_timeout_ms";
    static final String FIRST_FRAME_TIMEOUT_MILLIS = "ncpb.video.pipeline.first_frame_timeout_ms";
    static final String FIRST_FRAME_RECOVERY_ATTEMPTS = "ncpb.video.pipeline.first_frame_recovery_attempts";
    static final String OFFSCREEN_PAUSE_DECODE = "ncpb.video.offscreen.pause_decode";
    static final String OFFSCREEN_GRACE_MILLIS = "ncpb.video.offscreen.grace_ms";
    static final String OFFSCREEN_RESUME_RESTART_LAG_MILLIS =
            "ncpb.video.offscreen.resume_restart_lag_ms";
    static final String LEGACY_OFFSCREEN_RESUME_RESTART_LAG_MILLIS =
            "bili.video.offscreen.resume_restart_lag_ms";
    static final String OFFSCREEN_PREWARM_DOT_THRESHOLD = "ncpb.video.offscreen.prewarm_dot_threshold";
    static final String MAX_SOURCE_WIDTH = "ncpb.video.pipeline.max_source_width";
    static final String MAX_SOURCE_HEIGHT = "ncpb.video.pipeline.max_source_height";
    static final String IRIS_WARNING_VIEW_DEPTH_OFFSET =
            "ncpb.video.pipeline.iris_warning_placeholder_view_depth_offset";
    static final String IRIS_WARNING_LOCAL_DEPTH_OFFSET =
            "ncpb.video.pipeline.iris_warning_placeholder_local_depth_offset";
    static final String QUEUE_CAPACITY = "ncpb.video.pipeline.queue_capacity";
    static final String STARTUP_DROP_LAG_MILLIS = "ncpb.video.pipeline.startup_drop_lag_ms";
    static final String MAX_DECODE_LEAD_MILLIS = "ncpb.video.pipeline.max_decode_lead_ms";
    static final String UPLOAD_PUMP_WARN_MILLIS = "ncpb.video.pipeline.upload_pump_warn_ms";
    static final String EARLY_TOLERANCE_MILLIS = "ncpb.video.pipeline.early_tolerance_ms";
    static final String MAX_VISIBLE_LAG_MILLIS = "ncpb.video.pipeline.max_visible_lag_ms";
    static final String STARTUP_PREBUFFER_FRAMES = "ncpb.video.pipeline.startup_prebuffer_frames";
    static final String STARTUP_PREBUFFER_MAX_WAIT_MILLIS =
            "ncpb.video.pipeline.startup_prebuffer_max_wait_ms";
    static final String LOADING_PLACEHOLDER = "ncpb.video.pipeline.loading_placeholder";
    static final String WORLD_ANCHORED = "ncpb.video.world_anchor";
    static final String WORLD_ANCHOR_DISTANCE = "ncpb.video.world_anchor.distance";
    static final String AUDIO_SYNC_RANGE = "ncpb.video.turntable.sync_range";
    static final String RENDER_BACKEND = "ncpb.video.render.backend";
    static final String YUV_UPLOAD_PLANES = "ncpb.video.yuv.upload_planes";
    static final String PROJECTOR_TELEPORT_RESET_DISTANCE =
            "ncpb.video.projector.teleport_reset_distance";
    static final String YUV_IMMEDIATE_STAGE = "ncpb.video.yuv.immediate_stage";
    static final String YUV_IMMEDIATE_COORDS = "ncpb.video.yuv.immediate_coords";
    static final String YUV_IMMEDIATE_POSE = "ncpb.video.yuv.immediate_pose";
    static final String YUV_DEBUG_LOG = "ncpb.video.yuv.debug_log";
    static final String VIEW_DOT_THRESHOLD = "ncpb.video.render.view_dot_threshold";
    static final String VIEW_OCCLUSION_CHECK = "ncpb.video.render.occlusion_check";
    static final String VIEW_OCCLUSION_CACHE_MILLIS = "ncpb.video.render.occlusion_cache_ms";
    static final String LEGACY_VIEW_OCCLUSION_CACHE_MILLIS = "bili.video.render.occlusion_cache_ms";
    static final String VIEW_SAMPLE_EDGE_SCALE = "ncpb.video.render.visibility_sample_edge_scale";
    static final String MAX_RENDER_DISTANCE = "ncpb.video.max_render_distance";

    private VideoPipelineProperties() {
    }

    static boolean networkErrorPlaceholderEnabled() {
        return NcpbSystemProperties.booleanValue(NETWORK_ERROR_PLACEHOLDER, true);
    }

    static Upload upload() {
        return new Upload(
                NcpbSystemProperties.stringValue(PIXEL_MODE, "normal"),
                NcpbSystemProperties.booleanValue(FAST_NATIVE_UPLOAD, true),
                NcpbSystemProperties.booleanValue(NV12_PBO, true),
                nv12UvRg8Enabled());
    }

    public static boolean nv12UvRg8Enabled() {
        return NcpbSystemProperties.booleanValue(NV12_UV_RG8, true);
    }

    static Yuv yuv() {
        return new Yuv(
                NcpbSystemProperties.stringValue(YUV_MATRIX, "bt709_limited").toLowerCase(Locale.ROOT),
                NcpbSystemProperties.stringValue(YUV_SHADER_DEBUG, "").toUpperCase(Locale.ROOT),
                NcpbSystemProperties.booleanValue(YUV_NO_DEPTH_WRITE, false));
    }

    static Timing timing() {
        return new Timing(
                NcpbSystemProperties.longValue(AUDIO_LATENCY_COMPENSATION_MILLIS,
                        LEGACY_AUDIO_LATENCY_COMPENSATION_MILLIS, 0L),
                chaseWindowMillis(),
                NcpbSystemProperties.longValue(SLOWDOWN_WINDOW_MILLIS, 2_500L),
                NcpbSystemProperties.longValue(RUNTIME_LAG_RESTART_MILLIS, 1_500L),
                NcpbSystemProperties.longValue(RUNTIME_LAG_CONFIRM_MILLIS, 1_500L),
                NcpbSystemProperties.longValue(RUNTIME_LAG_RESTART_COOLDOWN_MILLIS, 5_000L),
                NcpbSystemProperties.longValue(DECODER_STABILIZATION_MILLIS, 8_000L),
                NcpbSystemProperties.longValue(DECODER_RESTART_CLOSE_TIMEOUT_MILLIS, 3_000L),
                NcpbSystemProperties.longValue(FIRST_FRAME_TIMEOUT_MILLIS, 20_000L),
                NcpbSystemProperties.intValue(FIRST_FRAME_RECOVERY_ATTEMPTS, 2));
    }

    static Offscreen offscreen() {
        return new Offscreen(
                NcpbSystemProperties.booleanValue(OFFSCREEN_PAUSE_DECODE, true),
                NcpbSystemProperties.longValue(OFFSCREEN_GRACE_MILLIS, 500L),
                NcpbSystemProperties.longValue(OFFSCREEN_RESUME_RESTART_LAG_MILLIS,
                        LEGACY_OFFSCREEN_RESUME_RESTART_LAG_MILLIS, 1_500L),
                NcpbSystemProperties.doubleValue(OFFSCREEN_PREWARM_DOT_THRESHOLD, -0.20D));
    }

    static Presentation presentation() {
        return new Presentation(
                NcpbSystemProperties.intValue(MAX_SOURCE_WIDTH, 4096),
                NcpbSystemProperties.intValue(MAX_SOURCE_HEIGHT, 2304),
                NcpbSystemProperties.doubleValue(IRIS_WARNING_VIEW_DEPTH_OFFSET, 0.03D),
                NcpbSystemProperties.floatValue(IRIS_WARNING_LOCAL_DEPTH_OFFSET, -0.01F),
                NcpbSystemProperties.intValue(QUEUE_CAPACITY, 3));
    }

    static long startupDropLagMillis() {
        return NcpbSystemProperties.longValue(STARTUP_DROP_LAG_MILLIS, 750L);
    }

    static long maxDecodeLeadMillis() {
        return NcpbSystemProperties.longValue(MAX_DECODE_LEAD_MILLIS, 250L);
    }

    static long uploadPumpWarnMillis() {
        return NcpbSystemProperties.longValue(UPLOAD_PUMP_WARN_MILLIS, 1_000L);
    }

    static long earlyToleranceMillis() {
        return NcpbSystemProperties.longValue(EARLY_TOLERANCE_MILLIS, 12L);
    }

    static long maxVisibleLagMillis() {
        return NcpbSystemProperties.longValue(MAX_VISIBLE_LAG_MILLIS, 250L);
    }

    static int startupPrebufferFrames() {
        return NcpbSystemProperties.intValue(STARTUP_PREBUFFER_FRAMES, 2);
    }

    static long startupPrebufferMaxWaitMillis() {
        return NcpbSystemProperties.longValue(STARTUP_PREBUFFER_MAX_WAIT_MILLIS, 250L);
    }

    static boolean loadingPlaceholderEnabled() {
        return NcpbSystemProperties.booleanValue(LOADING_PLACEHOLDER, true);
    }

    static long chaseWindowMillis() {
        return NcpbSystemProperties.longValue(CHASE_WINDOW_MILLIS, 10_000L);
    }

    static Billboard billboard() {
        return new Billboard(
                NcpbSystemProperties.booleanValue(WORLD_ANCHORED, true),
                NcpbSystemProperties.doubleValue(WORLD_ANCHOR_DISTANCE, 6.0D),
                NcpbSystemProperties.doubleValue(AUDIO_SYNC_RANGE, 96.0D),
                NcpbSystemProperties.stringValue(RENDER_BACKEND, "nv12").toLowerCase(Locale.ROOT),
                NcpbSystemProperties.booleanValue(YUV_UPLOAD_PLANES, true),
                NcpbSystemProperties.doubleValue(PROJECTOR_TELEPORT_RESET_DISTANCE, 16.0D));
    }

    static YuvImmediate yuvImmediate() {
        return new YuvImmediate(
                NcpbSystemProperties.stringValue(YUV_IMMEDIATE_STAGE, "after_level")
                        .toLowerCase(Locale.ROOT),
                NcpbSystemProperties.stringValue(YUV_IMMEDIATE_COORDS, "camera_relative")
                        .toLowerCase(Locale.ROOT),
                NcpbSystemProperties.stringValue(YUV_IMMEDIATE_POSE, "identity")
                        .toLowerCase(Locale.ROOT),
                NcpbSystemProperties.booleanValue(YUV_DEBUG_LOG, false));
    }

    static Visibility visibility() {
        long cacheMillis = Math.max(0L, NcpbSystemProperties.longValue(
                VIEW_OCCLUSION_CACHE_MILLIS, LEGACY_VIEW_OCCLUSION_CACHE_MILLIS, 150L));
        return new Visibility(
                NcpbSystemProperties.doubleValue(VIEW_DOT_THRESHOLD, 0.12D),
                NcpbSystemProperties.booleanValue(VIEW_OCCLUSION_CHECK, true),
                TimeUnit.MILLISECONDS.toNanos(cacheMillis),
                NcpbSystemProperties.doubleValue(VIEW_SAMPLE_EDGE_SCALE, 0.86D),
                NcpbSystemProperties.doubleValue(MAX_RENDER_DISTANCE, 64.0D));
    }

    record Upload(String pixelMode, boolean fastNativeUploadEnabled, boolean nv12PboEnabled,
            boolean nv12UvRg8Enabled) {
    }

    record Yuv(String matrix, String shaderDebug, boolean depthWriteDisabled) {
    }

    record Timing(long audioLatencyCompensationMillis, long chaseWindowMillis, long slowdownWindowMillis,
            long runtimeLagRestartMillis, long runtimeLagConfirmMillis, long runtimeLagRestartCooldownMillis,
            long decoderStabilizationMillis, long decoderRestartCloseTimeoutMillis, long firstFrameTimeoutMillis,
            int firstFrameRecoveryAttempts) {
    }

    record Offscreen(boolean pauseDecode, long graceMillis, long resumeRestartLagMillis,
            double prewarmDotThreshold) {
    }

    record Presentation(int maxSourceWidth, int maxSourceHeight, double irisWarningViewDepthOffset,
            float irisWarningLocalDepthOffset, int queueCapacity) {
    }

    record Billboard(boolean worldAnchored, double worldAnchorDistance, double audioSyncRange,
            String renderBackend, boolean yuvUploadPlanes, double projectorTeleportResetDistance) {
        Billboard {
            worldAnchorDistance = Math.max(0.0D, worldAnchorDistance);
            audioSyncRange = Math.max(0.0D, audioSyncRange);
            projectorTeleportResetDistance = Math.max(0.0D, projectorTeleportResetDistance);
        }

        double audioSyncRangeSqr() {
            return squaredDistance(audioSyncRange);
        }

        double projectorTeleportResetDistanceSqr() {
            return squaredDistance(projectorTeleportResetDistance);
        }
    }

    record YuvImmediate(String stage, String coordinates, String pose, boolean debugLog) {
    }

    record Visibility(double viewDotThreshold, boolean occlusionCheck, long occlusionCacheNanos,
            double sampleEdgeScale, double maxRenderDistance) {
        Visibility {
            viewDotThreshold = Math.max(-1.0D, Math.min(1.0D, viewDotThreshold));
            occlusionCacheNanos = Math.max(0L, occlusionCacheNanos);
            sampleEdgeScale = Math.max(0.0D, Math.min(1.0D, sampleEdgeScale));
            maxRenderDistance = Math.max(0.0D, maxRenderDistance);
        }

        double maxRenderDistanceSqr() {
            return squaredDistance(maxRenderDistance);
        }
    }

    private static double squaredDistance(double value) {
        double maxRoot = Math.sqrt(Double.MAX_VALUE);
        return value >= maxRoot ? Double.MAX_VALUE : value * value;
    }
}
