package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleVideoStatePolicy;

/** Pure resource mapping for the control-console video presentation states. */
final class ControlConsoleVideoArtwork {
    private static final String ROOT = "textures/gui/control_console_video/";

    private ControlConsoleVideoArtwork() {
    }

    static String texturePath(ControlConsoleVideoStatePolicy.State state) {
        return switch (state) {
            case IDLE -> ROOT + "idle.png";
            case BUFFERING -> ROOT + "buffering.png";
            case ERROR -> ROOT + "error.png";
            case ACTIVE -> throw new IllegalArgumentException("ACTIVE control-console video requires a real frame");
        };
    }

    static boolean loadingProgressOverlay(ControlConsoleVideoStatePolicy.State state) {
        return state == ControlConsoleVideoStatePolicy.State.BUFFERING;
    }
}
