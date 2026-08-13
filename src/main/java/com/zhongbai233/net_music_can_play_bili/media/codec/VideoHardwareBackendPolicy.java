package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.util.Locale;

/** Classifies the native backend string without loading JNI or client classes. */
final class VideoHardwareBackendPolicy {
    private VideoHardwareBackendPolicy() {
    }

    static boolean isHardwareBackend(String actualHwaccel) {
        if (actualHwaccel == null || actualHwaccel.isBlank()) {
            return false;
        }
        String normalized = actualHwaccel.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "d3d11va", "dxva2", "cuda", "qsv", "videotoolbox", "vaapi" -> true;
            default -> false;
        };
    }
}
