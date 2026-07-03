package com.zhongbai233.net_music_can_play_bili.item.pad;

public enum PadTriggerMode {
    MANUAL,
    ENTER_RADIUS;

    public static PadTriggerMode byName(String name) {
        if (name == null || name.isBlank()) {
            return MANUAL;
        }
        for (PadTriggerMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return MANUAL;
    }
}