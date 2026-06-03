package com.zhongbai233.net_music_can_play_bili.client;

import java.util.Locale;

/**
 * 视频播放功能开关与可选诊断参数入口
 */
public final class VideoFeatureFlags {
    public static final boolean ADVANCED_FEATURES = Boolean.getBoolean("bili.video.advanced_features");

    private VideoFeatureFlags() {
    }

    public static boolean benchFeaturesEnabled() {
        return ADVANCED_FEATURES && Boolean.getBoolean("bili.video.enable_bench_features");
    }

    public static boolean advancedBoolean(String key, boolean defaultValue) {
        return ADVANCED_FEATURES ? Boolean.parseBoolean(System.getProperty(key, Boolean.toString(defaultValue)))
                : defaultValue;
    }

    public static int advancedInt(String key, int defaultValue) {
        return ADVANCED_FEATURES ? Integer.getInteger(key, defaultValue) : defaultValue;
    }

    public static long advancedLong(String key, long defaultValue) {
        return ADVANCED_FEATURES ? Long.getLong(key, defaultValue) : defaultValue;
    }

    public static String advancedString(String key, String defaultValue) {
        return ADVANCED_FEATURES ? System.getProperty(key, defaultValue) : defaultValue;
    }

    public static String[] autoHwaccelCandidates() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new String[] { "d3d11va", "dxva2", "cuda", "qsv", "none" };
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new String[] { "videotoolbox", "none" };
        }
        return new String[] { "vaapi", "cuda", "qsv", "none" };
    }

    public static String[] requestedHwaccelCandidates() {
        if (!ADVANCED_FEATURES) {
            return autoHwaccelCandidates();
        }
        String raw = System.getProperty("bili.video.native.hwaccel", "auto").trim();
        if (raw.isBlank() || "auto".equalsIgnoreCase(raw)) {
            return autoHwaccelCandidates();
        }
        return new String[] { raw, "none" };
    }
}
