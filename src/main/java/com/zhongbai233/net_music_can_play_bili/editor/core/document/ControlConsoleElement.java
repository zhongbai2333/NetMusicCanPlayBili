package com.zhongbai233.net_music_can_play_bili.editor.core.document;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** 中控台文档中的不可变空间元素。 */
public record ControlConsoleElement(UUID elementId, Type type, String name,
        float distance, float offsetX, float offsetY, float height, float aspect,
    float yaw, float pitch, float roll,
    String contentMode, String text, boolean followLyrics, boolean showTranslation,
    float textScale, int color, float volume, int channelIndex, float maxDistance, boolean autoMixJoc,
    int translationColor, int backgroundColor, Alignment alignment, float maxWidth, boolean wrap,
    boolean enabled, boolean locked) {
    public static final int MAX_NAME_LENGTH = 64;
    public static final int DEFAULT_TRANSLATION_COLOR = 0xFFB8D8FF;
    public static final int DEFAULT_BACKGROUND_COLOR = 0x40000000;
    public static final float DEFAULT_MAX_WIDTH = 0.0F;

    public ControlConsoleElement {
        elementId = Objects.requireNonNull(elementId, "elementId");
        Objects.requireNonNull(type, "type");
        name = Objects.requireNonNull(name, "name").trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("element name must contain 1-64 characters");
        }
        validateFinite(distance, "distance");
        validateFinite(offsetX, "offsetX");
        validateFinite(offsetY, "offsetY");
        validatePositive(height, "height");
        validatePositive(aspect, "aspect");
        validatePositive(height * aspect, "width");
        validateFinite(yaw, "yaw");
        validateFinite(pitch, "pitch");
        validateFinite(roll, "roll");
        contentMode = Objects.requireNonNull(contentMode, "contentMode").trim();
        if (contentMode.isEmpty() || contentMode.length() > 32) {
            throw new IllegalArgumentException("contentMode must contain 1-32 characters");
        }
        if (!validContentMode(type, contentMode)) {
            throw new IllegalArgumentException("contentMode is not valid for element type " + type);
        }
        text = Objects.requireNonNull(text, "text");
        if (text.length() > 4096) {
            throw new IllegalArgumentException("text must contain at most 4096 characters");
        }
        validatePositive(textScale, "textScale");
        validateFinite(volume, "volume");
        validatePositive(maxDistance, "maxDistance");
        alignment = Objects.requireNonNull(alignment, "alignment");
        validateFinite(maxWidth, "maxWidth");
        if (volume > 1.0F || volume < 0.0F || channelIndex < -1 || channelIndex > 11 || maxWidth < 0.0F) {
            throw new IllegalArgumentException("element content field is outside its semantic domain");
        }
    }

    /** 旧 schema v2 空间构造器；内容字段使用类型默认值。 */
    public ControlConsoleElement(Type type, String name, float distance, float offsetX, float offsetY,
            float height, float aspect, float yaw, float pitch, float roll) {
        this(UUID.randomUUID(), type, name, distance, offsetX, offsetY, height, aspect, yaw, pitch, roll,
                type == Type.SCREEN ? "SOURCE" : type == Type.SUBTITLE ? "LYRICS" : "SOURCE",
            "", type == Type.SUBTITLE, true, 1.0F, 0xFFFFFFFF, 1.0F, 0, 32.0F, false, true, false);
    }

        public ControlConsoleElement(UUID elementId, Type type, String name, float distance, float offsetX,
            float offsetY, float height, float aspect, float yaw, float pitch, float roll) {
        this(elementId, type, name, distance, offsetX, offsetY, height, aspect, yaw, pitch, roll,
            type == Type.SCREEN ? "SOURCE" : type == Type.SUBTITLE ? "LYRICS" : "SOURCE",
            "", type == Type.SUBTITLE, true, 1.0F, 0xFFFFFFFF, 1.0F, 0, 32.0F, false, true, false);
        }

        /** v4 显式身份构造器。 */
        public ControlConsoleElement(UUID elementId, Type type, String name, float distance, float offsetX, float offsetY,
            float height, float aspect, float yaw, float pitch, float roll, String contentMode, String text,
            boolean followLyrics, boolean showTranslation, float textScale, int color, float volume, int channelIndex,
            float maxDistance, boolean autoMixJoc, boolean enabled) {
        this(elementId, type, name, distance, offsetX, offsetY, height, aspect, yaw, pitch, roll, contentMode, text,
            followLyrics, showTranslation, textScale, color, volume, channelIndex, maxDistance, autoMixJoc,
            enabled, false);
        }

    /** 兼容现有 v3 完整内容构造器。 */
    public ControlConsoleElement(Type type, String name, float distance, float offsetX, float offsetY,
            float height, float aspect, float yaw, float pitch, float roll, String contentMode, String text,
            boolean followLyrics, boolean showTranslation, float textScale, int color, float volume, int channelIndex,
            float maxDistance, boolean autoMixJoc, boolean enabled) {
        this(UUID.randomUUID(), type, name, distance, offsetX, offsetY, height, aspect, yaw, pitch, roll, contentMode,
            text, followLyrics, showTranslation, textScale, color, volume, channelIndex, maxDistance, autoMixJoc,
            enabled, false);
    }

            /** 兼容现有 v4 完整内容与锁定构造器。 */
            public ControlConsoleElement(UUID elementId, Type type, String name, float distance, float offsetX,
                float offsetY, float height, float aspect, float yaw, float pitch, float roll, String contentMode,
                String text, boolean followLyrics, boolean showTranslation, float textScale, int color, float volume,
                int channelIndex, float maxDistance, boolean autoMixJoc, boolean enabled, boolean locked) {
            this(elementId, type, name, distance, offsetX, offsetY, height, aspect, yaw, pitch, roll, contentMode, text,
                followLyrics, showTranslation, textScale, color, volume, channelIndex, maxDistance, autoMixJoc,
                DEFAULT_TRANSLATION_COLOR, DEFAULT_BACKGROUND_COLOR, Alignment.CENTER, DEFAULT_MAX_WIDTH, false,
                enabled, locked);
            }

            /** 新链路使用的显式完整构造器（自动生成元素身份）。 */
            public ControlConsoleElement(Type type, String name, float distance, float offsetX, float offsetY,
                float height, float aspect, float yaw, float pitch, float roll, String contentMode, String text,
                boolean followLyrics, boolean showTranslation, float textScale, int color, float volume, int channelIndex,
                float maxDistance, boolean autoMixJoc, int translationColor, int backgroundColor, Alignment alignment,
                float maxWidth, boolean wrap, boolean enabled) {
            this(UUID.randomUUID(), type, name, distance, offsetX, offsetY, height, aspect, yaw, pitch, roll, contentMode,
                text, followLyrics, showTranslation, textScale, color, volume, channelIndex, maxDistance, autoMixJoc,
                translationColor, backgroundColor, alignment, maxWidth, wrap, enabled, false);
            }

    private static void validateFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void validatePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0F) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static boolean validContentMode(Type type, String mode) {
        return switch (type) {
            case SCREEN, AUDIO -> "SOURCE".equals(mode);
            case SUBTITLE -> "LYRICS".equals(mode) || "FIXED".equals(mode)
                    || "SCROLL_MAIN".equals(mode) || "SCROLL_TRANSLATION".equals(mode);
        };
    }

    public enum Type {
        SCREEN,
        SUBTITLE,
        AUDIO;

        public static Type parse(String value) {
            return Type.valueOf(Objects.requireNonNull(value, "value").trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum Alignment {
        LEFT,
        CENTER,
        RIGHT;

        public static Alignment parse(String value) {
            return Alignment.valueOf(Objects.requireNonNull(value, "value").trim().toUpperCase(Locale.ROOT));
        }
    }
}