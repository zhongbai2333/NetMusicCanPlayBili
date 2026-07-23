package com.zhongbai233.net_music_can_play_bili.bili;

import java.util.Arrays;

/**
 * 将音响界面的 7.1.4 逻辑声道映射到媒体实际解码出的声道布局。
 * <p>
 * 普通网络音频通常只有 2、6 或 8 个声道，不能直接把 0..11 的逻辑编号
 * 当作 PCM 数组下标，否则中置、后环绕和顶部音响会被静默跳过。
 */
final class SpeakerChannelMixer {
    private SpeakerChannelMixer() {
    }

    static float[] baseMix(float[][] pcmChannels, int logicalChannel) {
        int samples = sampleCount(pcmChannels);
        if (logicalChannel < 0 || samples == 0) {
            return null;
        }
        int channelCount = pcmChannels.length;
        int[] sources = sourceChannels(logicalChannel, channelCount);
        if (sources.length == 0) {
            return null;
        }
        if (sources.length == 1) {
            float[] source = pcmChannels[sources[0]];
            return source != null ? source.clone() : new float[samples];
        }
        float[] mixed = new float[samples];
        float gain = 1.0f / sources.length;
        for (int sourceChannel : sources) {
            mixInto(mixed, pcmChannels[sourceChannel], gain);
        }
        return mixed;
    }

    static boolean isSourceClaimed(int sourceChannel, int channelCount, Iterable<SpeakerAudioRelay> relays) {
        if (sourceChannel < 0 || sourceChannel >= channelCount || relays == null) {
            return false;
        }
        for (SpeakerAudioRelay relay : relays) {
            if (relay != null && contains(sourceChannels(relay.getChannelIndex(), channelCount), sourceChannel)) {
                return true;
            }
        }
        return false;
    }

    static int primarySourceChannel(int logicalChannel, int channelCount) {
        int[] sources = sourceChannels(logicalChannel, channelCount);
        return sources.length > 0 ? sources[0] : -1;
    }

    /**
     * 返回 FFmpeg PCM 布局中的源声道。
     * 2ch: FL, FR；5.1: FL, FR, FC, LFE, SL, SR；
     * 7.1: FL, FR, FC, LFE, BL, BR, SL, SR。
     */
    static int[] sourceChannels(int logicalChannel, int channelCount) {
        if (logicalChannel < 0 || channelCount <= 0) {
            return new int[0];
        }
        if (channelCount == 1) {
            return new int[] { 0 };
        }
        if (channelCount == 2) {
            return switch (logicalChannel) {
                case 0, 4, 6, 8, 10 -> new int[] { 0 }; // 左侧与左高度
                case 1, 5, 7, 9, 11 -> new int[] { 1 }; // 右侧与右高度
                case 2, 3 -> new int[] { 0, 1 }; // 中置/LFE 使用立体声等权降混
                default -> new int[0];
            };
        }
        if (channelCount == 6) {
            return switch (logicalChannel) {
                case 0, 1, 2, 3, 4, 5 -> new int[] { logicalChannel };
                case 6, 8, 10 -> new int[] { 4 }; // 左后/左高度回落到 SL
                case 7, 9, 11 -> new int[] { 5 }; // 右后/右高度回落到 SR
                default -> new int[0];
            };
        }
        if (channelCount >= 8) {
            return switch (logicalChannel) {
                case 0, 1, 2, 3 -> new int[] { logicalChannel };
                case 4, 8 -> new int[] { 6 }; // Ls/Ltf -> SL
                case 5, 9 -> new int[] { 7 }; // Rs/Rtf -> SR
                case 6, 10 -> new int[] { 4 }; // Lrs/Ltr -> BL
                case 7, 11 -> new int[] { 5 }; // Rrs/Rtr -> BR
                default -> new int[0];
            };
        }
        return logicalChannel < channelCount ? new int[] { logicalChannel } : new int[0];
    }

    static void mixInto(float[] target, float[] source, float gain) {
        if (target == null || source == null || gain <= 0.0f) {
            return;
        }
        int n = Math.min(target.length, source.length);
        for (int i = 0; i < n; i++) {
            target[i] = softClip(target[i] + source[i] * gain);
        }
    }

    private static int sampleCount(float[][] pcmChannels) {
        if (pcmChannels == null) {
            return 0;
        }
        return Arrays.stream(pcmChannels)
                .filter(channel -> channel != null)
                .mapToInt(channel -> channel.length)
                .max()
                .orElse(0);
    }

    private static boolean contains(int[] values, int value) {
        for (int candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    private static float softClip(float sample) {
        return Math.max(-1.0f, Math.min(1.0f, sample));
    }
}