package com.zhongbai233.net_music_can_play_bili.bili;

/** Stateless JOC channel mapping, level statistics, and bed-speaker layout calculations. */
final class DolbySpatialLayout {
    private DolbySpatialLayout() {
    }

    static float[][] buildJocDownmixBlock(float[][] pcmChannels, int pcmChannelCount, int jocChannelCount,
            int offset) {
        int[] map = channelMapForJoc(pcmChannelCount, jocChannelCount);
        float[][] block = new float[map.length][256];
        for (int channel = 0; channel < map.length; channel++) {
            int source = map[channel];
            if (source >= 0 && source < pcmChannels.length && offset < pcmChannels[source].length) {
                System.arraycopy(pcmChannels[source], offset, block[channel], 0,
                        Math.min(256, pcmChannels[source].length - offset));
            }
        }
        return block;
    }

    static float[] rmsByObject(float[][] pcm, int count) {
        float[] result = new float[count];
        if (pcm == null) {
            return result;
        }
        for (int object = 0; object < count && object < pcm.length; object++) {
            double sum = 0.0;
            for (float sample : pcm[object]) {
                sum += sample * sample;
            }
            result[object] = pcm[object].length == 0 ? 0f : (float) Math.sqrt(sum / pcm[object].length);
        }
        return result;
    }

    static float[] peakByObject(float[][] pcm, int count) {
        float[] result = new float[count];
        if (pcm == null) {
            return result;
        }
        for (int object = 0; object < count && object < pcm.length; object++) {
            float peak = 0f;
            for (float sample : pcm[object]) {
                peak = Math.max(peak, Math.abs(sample));
            }
            result[object] = peak;
        }
        return result;
    }

    static int countActiveObjects(float[] rms) {
        int active = 0;
        for (float value : rms) {
            if (value > 1.0e-5f) {
                active++;
            }
        }
        return active;
    }

    static float max(float[] values) {
        float max = 0f;
        for (float value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    static float[][] computeBedPositions(int channelCount, float radius) {
        if (channelCount == 6) {
            return compute5Point1Positions(radius);
        }
        if (channelCount == 8) {
            return compute7Point1Positions(radius);
        }
        float[][] positions = new float[channelCount][3];
        double step = 2 * Math.PI / channelCount;
        double start = 0;
        if (channelCount == 2) {
            step = Math.PI / 3;
            start = -Math.PI / 6;
        }
        for (int channel = 0; channel < channelCount; channel++) {
            double angle = start + channel * step;
            positions[channel][0] = (float) (Math.sin(angle) * radius);
            positions[channel][2] = (float) (Math.cos(angle) * radius);
        }
        return positions;
    }

    static String[] bedChannelNames(int channels) {
        return switch (channels) {
            case 6 -> new String[] { "FL", "FR", "FC", "LFE", "SL", "SR" };
            case 8 -> new String[] { "FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR" };
            default -> null;
        };
    }

    static int centerChannelIndex(int channels) {
        return channels == 6 || channels == 8 ? 2 : 0;
    }

    private static int[] channelMapForJoc(int pcmChannels, int jocChannels) {
        if (pcmChannels >= 6 && jocChannels == 5) {
            return new int[] { 0, 2, 1, 4, 5 };
        }
        if (pcmChannels >= 8 && jocChannels == 7) {
            return new int[] { 0, 2, 1, 6, 7, 4, 5 };
        }
        int channels = Math.min(pcmChannels, jocChannels);
        int[] map = new int[channels];
        for (int channel = 0; channel < channels; channel++) {
            map[channel] = channel;
        }
        return map;
    }

    private static float[][] compute5Point1Positions(float radius) {
        float[][] positions = new float[6][3];
        double[] angles = { -Math.PI / 6, Math.PI / 6, 0, 0, -Math.PI * 11 / 18, Math.PI * 11 / 18 };
        for (int channel = 0; channel < positions.length; channel++) {
            positions[channel][0] = (float) (Math.sin(angles[channel]) * radius);
            positions[channel][2] = (float) (Math.cos(angles[channel]) * radius);
        }
        positions[3] = new float[3];
        return positions;
    }

    private static float[][] compute7Point1Positions(float radius) {
        float[][] positions = new float[8][3];
        double[] angles = {
                -Math.PI / 6, Math.PI / 6, 0, 0,
                -Math.PI * 5 / 6, Math.PI * 5 / 6, -Math.PI / 2, Math.PI / 2
        };
        for (int channel = 0; channel < positions.length; channel++) {
            positions[channel][0] = (float) (Math.sin(angles[channel]) * radius);
            positions[channel][2] = (float) (Math.cos(angles[channel]) * radius);
        }
        positions[3] = new float[3];
        return positions;
    }
}
