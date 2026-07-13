package com.zhongbai233.net_music_can_play_bili.blockentity;

/** 现代化唱片机对漏斗和管道的自动提取策略。 */
public enum TurntableExtractionMode {
    AFTER_PLAYBACK("after_playback", "播完提取"),
    ALWAYS("always", "自由提取");

    private final String serializedName;
    private final String displayName;

    TurntableExtractionMode(String serializedName, String displayName) {
        this.serializedName = serializedName;
        this.displayName = displayName;
    }

    public String serializedName() {
        return serializedName;
    }

    public String displayName() {
        return displayName;
    }

    public TurntableExtractionMode next() {
        return this == AFTER_PLAYBACK ? ALWAYS : AFTER_PLAYBACK;
    }

    public static TurntableExtractionMode byName(String name) {
        for (TurntableExtractionMode mode : values()) {
            if (mode.serializedName.equals(name)) {
                return mode;
            }
        }
        return AFTER_PLAYBACK;
    }
}