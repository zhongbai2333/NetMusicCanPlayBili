package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;

/** 不依赖 Minecraft 的中控台硬范围消费状态机。 */
public final class ControlConsoleRangeGate {
    public static final double FADE_BAND = AudioPlaybackRange.ZONE_FADE_BAND;
    public static final double REENTRY_INSET = AudioPlaybackRange.ZONE_REENTRY_INSET;

    private ControlConsoleRangeGate() {
    }

    public static Result evaluate(boolean previouslyActive, double relativeX, double relativeY, double relativeZ,
            double halfX, double halfY, double halfZ) {
        AudioPlaybackRange.ZoneResult result = AudioPlaybackRange.evaluateAabb(previouslyActive,
                relativeX, relativeY, relativeZ, halfX, halfY, halfZ);
        return new Result(result.active(), result.gain());
    }

    public record Result(boolean active, float gain) {
    }
}
