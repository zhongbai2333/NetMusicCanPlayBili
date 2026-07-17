package com.zhongbai233.net_music_can_play_bili.util;

import java.util.function.Function;

/**
 * 集中读取本模组的 JVM 系统属性，并为历史属性名提供兼容回退。
 *
 * <p>
 * 候选键按顺序解析；不存在、空白或格式错误的值会被跳过。
 * </p>
 */
public final class NcpbSystemProperties {
    private NcpbSystemProperties() {
    }

    public static boolean booleanValue(String key, boolean fallback) {
        return booleanValue(key, null, fallback);
    }

    public static boolean booleanValue(String key, String legacyKey, boolean fallback) {
        Boolean value = firstValid(NcpbSystemProperties::parseBoolean, key, legacyKey);
        return value != null ? value : fallback;
    }

    public static int intValue(String key, int fallback) {
        return intValue(key, null, fallback);
    }

    public static int intValue(String key, String legacyKey, int fallback) {
        Integer value = firstValid(Integer::valueOf, key, legacyKey);
        return value != null ? value : fallback;
    }

    public static long longValue(String key, long fallback) {
        return longValue(key, null, fallback);
    }

    public static long longValue(String key, String legacyKey, long fallback) {
        Long value = firstValid(Long::valueOf, key, legacyKey);
        return value != null ? value : fallback;
    }

    public static double doubleValue(String key, double fallback) {
        return doubleValue(key, null, fallback);
    }

    public static double doubleValue(String key, String legacyKey, double fallback) {
        Double value = firstValid(NcpbSystemProperties::parseFiniteDouble, key, legacyKey);
        return value != null ? value : fallback;
    }

    private static Boolean parseBoolean(String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new IllegalArgumentException("not a boolean");
    }

    private static Double parseFiniteDouble(String raw) {
        double value = Double.parseDouble(raw);
        if (!Double.isFinite(value)) {
            throw new NumberFormatException("non-finite double");
        }
        return value;
    }

    private static <T> T firstValid(Function<String, T> parser, String... keys) {
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String raw = System.getProperty(key);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                return parser.apply(raw.trim());
            } catch (IllegalArgumentException ignored) {
                // Try the next legacy key before falling back to the default value.
            }
        }
        return null;
    }
}