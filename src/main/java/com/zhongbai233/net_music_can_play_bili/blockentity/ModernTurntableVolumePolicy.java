package com.zhongbai233.net_music_can_play_bili.blockentity;

/** Server-side audience action for a modern-turntable volume transition. */
public final class ModernTurntableVolumePolicy {
    public enum Action {
        NONE,
        APPLY_ONLY,
        STOP_MUTED,
        RESYNC_UNMUTED
    }

    private ModernTurntableVolumePolicy() {
    }

    public static Action decide(int previousPerMille, int nextPerMille, boolean playing) {
        int previous = clamp(previousPerMille);
        int next = clamp(nextPerMille);
        if (previous == next) {
            return Action.NONE;
        }
        if (!playing) {
            return Action.APPLY_ONLY;
        }
        if (previous > 0 && next == 0) {
            return Action.STOP_MUTED;
        }
        if (previous == 0 && next > 0) {
            return Action.RESYNC_UNMUTED;
        }
        return Action.APPLY_ONLY;
    }

    public static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }
}
