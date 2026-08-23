package com.zhongbai233.net_music_can_play_bili.client.debug;

/** Independent visibility controls for the diagnostic HUD and world-space ranges. */
public enum PlaybackDebugMode {
    OFF(false, false),
    UI(true, false),
    RANGE(false, true),
    BOTH(true, true);

    private final boolean hudEnabled;
    private final boolean rangeEnabled;

    PlaybackDebugMode(boolean hudEnabled, boolean rangeEnabled) {
        this.hudEnabled = hudEnabled;
        this.rangeEnabled = rangeEnabled;
    }

    public boolean hudEnabled() {
        return hudEnabled;
    }

    public boolean rangeEnabled() {
        return rangeEnabled;
    }

    public boolean enabled() {
        return hudEnabled || rangeEnabled;
    }
}
