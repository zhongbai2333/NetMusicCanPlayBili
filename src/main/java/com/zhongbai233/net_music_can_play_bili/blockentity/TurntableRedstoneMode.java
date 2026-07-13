package com.zhongbai233.net_music_can_play_bili.blockentity;

/** 现代化唱片机的红石电平控制模式。 */
public enum TurntableRedstoneMode {
    HIGH_SIGNAL("high_signal", "高信号"),
    LOW_SIGNAL("low_signal", "低信号"),
    PULSE_TOGGLE("pulse_toggle", "脉冲切换"),
    IGNORE("ignore", "忽略红石");

    private final String serializedName;
    private final String displayName;

    TurntableRedstoneMode(String serializedName, String displayName) {
        this.serializedName = serializedName;
        this.displayName = displayName;
    }

    public String serializedName() {
        return serializedName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean shouldPlay(boolean powered) {
        return switch (this) {
            case HIGH_SIGNAL -> powered;
            case LOW_SIGNAL -> !powered;
            case PULSE_TOGGLE -> true;
            case IGNORE -> true;
        };
    }

    public TurntableRedstoneMode next() {
        return switch (this) {
            case HIGH_SIGNAL -> LOW_SIGNAL;
            case LOW_SIGNAL -> PULSE_TOGGLE;
            case PULSE_TOGGLE -> IGNORE;
            case IGNORE -> HIGH_SIGNAL;
        };
    }

    public static TurntableRedstoneMode byName(String name) {
        for (TurntableRedstoneMode mode : values()) {
            if (mode.serializedName.equals(name)) {
                return mode;
            }
        }
        return IGNORE;
    }
}