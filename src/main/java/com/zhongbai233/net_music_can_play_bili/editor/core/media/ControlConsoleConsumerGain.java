package com.zhongbai233.net_music_can_play_bili.editor.core.media;

/** 合成 hardRange 空间增益与进入/退出时间包络，供视频、字幕和音频保持一致。 */
public final class ControlConsoleConsumerGain {
    private ControlConsoleConsumerGain() {
    }

    public static float combine(float rangeGain, float envelopeGain) {
        if (!Float.isFinite(rangeGain) || !Float.isFinite(envelopeGain)) {
            return 0.0F;
        }
        return Math.clamp(rangeGain, 0.0F, 1.0F) * Math.clamp(envelopeGain, 0.0F, 1.0F);
    }
}