package com.zhongbai233.net_music_can_play_bili.editor.core.media;

/** 中控台屏幕画质档位与 7.1.4 音响声道的稳定持久化映射。 */
public final class ControlConsoleMediaSettings {
    private static final int[] VIDEO_QUALITIES = { 116, 112, 80, 64, 32, 16, 120, 127 };
    private static final String[] VIDEO_LABELS = {
        "1080P60", "1080P+", "1080P", "720P", "480P", "360P", "4K", "8K"
    };
    private static final String[] AUDIO_CHANNEL_LABELS = {
        "L", "R", "C", "LFE", "Ls", "Rs", "Lrs", "Rrs", "Ltf", "Rtf", "Ltr", "Rtr"
    };

    private ControlConsoleMediaSettings() {
    }

    public static int normalizeVideoQualityIndex(int index) {
        return Math.clamp(index, 0, VIDEO_QUALITIES.length - 1);
    }

    public static int videoQualityCeiling(int index) {
        return VIDEO_QUALITIES[normalizeVideoQualityIndex(index)];
    }

    public static String videoQualityLabel(int index) {
        return VIDEO_LABELS[normalizeVideoQualityIndex(index)];
    }

    public static int nextVideoQualityIndex(int index) {
        return (normalizeVideoQualityIndex(index) + 1) % VIDEO_QUALITIES.length;
    }

    public static int normalizeAudioChannel(int channel) {
        return Math.clamp(channel, -1, AUDIO_CHANNEL_LABELS.length - 1);
    }

    public static String audioChannelLabel(int channel) {
        int normalized = normalizeAudioChannel(channel);
        return normalized < 0 ? "静音" : AUDIO_CHANNEL_LABELS[normalized];
    }

    public static int nextAudioChannel(int channel) {
        int normalized = normalizeAudioChannel(channel);
        return normalized >= AUDIO_CHANNEL_LABELS.length - 1 ? -1 : normalized + 1;
    }
}
