package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.Locale;

/** Stable diagnostic reasons shared by logs and user-visible status text. */
public final class VideoFallbackReason {
    public static final String NO_AV1_STREAM = "no-av1-stream";
    public static final String AV1_HARDWARE_UNAVAILABLE = "av1-hardware-unavailable";
    public static final String AV1_PROFILE_INCOMPATIBLE = "av1-profile-incompatible";
    public static final String AV1_STARTUP_FAILURE = "av1-startup-failure";
    public static final String PERFORMANCE_LOW_FPS = "performance-low-fps";
    public static final String PERFORMANCE_GROWING_DRIFT = "performance-growing-av-drift";
    public static final String NO_H264_CANDIDATE = "no-h264-candidate";

    private VideoFallbackReason() {
    }

    public static String classifyAv1StartupFailure(Throwable error, boolean h264Available) {
        if (!h264Available) {
            return NO_H264_CANDIDATE;
        }
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                text.append(' ').append(current.getMessage().toLowerCase(Locale.ROOT));
            }
        }
        String value = text.toString();
        if (value.contains("profile") || value.contains("bit depth") || value.contains("10-bit")
                || value.contains("av1c") || value.contains("extradata") || value.contains("config obu")) {
            return AV1_PROFILE_INCOMPATIBLE;
        }
        if (value.contains("hardware") || value.contains("hwaccel") || value.contains("backend")
                || value.contains("硬件") || value.contains("actual=cpu") || value.contains("actual=none")
                || value.contains("actual=unknown")) {
            return AV1_HARDWARE_UNAVAILABLE;
        }
        return AV1_STARTUP_FAILURE;
    }

    public static String userLabel(String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        return switch (reason) {
            case NO_AV1_STREAM -> "无AV1流";
            case AV1_HARDWARE_UNAVAILABLE -> "AV1硬解不可用";
            case AV1_PROFILE_INCOMPATIBLE -> "AV1 profile不兼容";
            case AV1_STARTUP_FAILURE -> "AV1启动失败";
            case PERFORMANCE_LOW_FPS -> "性能降级(FPS)";
            case PERFORMANCE_GROWING_DRIFT -> "性能降级(音画差)";
            case NO_H264_CANDIDATE -> "无H.264后备";
            default -> reason;
        };
    }
}
