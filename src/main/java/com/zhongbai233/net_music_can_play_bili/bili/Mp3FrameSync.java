package com.zhongbai233.net_music_can_play_bili.bili;

/** Pure MP3 frame sync scanner used by ranged MP3 fallback seeking. */
public final class Mp3FrameSync {
    private static final int[] MP3_MPEG1_LAYER1_BITRATES = { 0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352,
            384, 416, 448, 0 };
    private static final int[] MP3_MPEG1_LAYER2_BITRATES = { 0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256,
            320, 384, 0 };
    private static final int[] MP3_MPEG1_LAYER3_BITRATES = { 0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224,
            256, 320, 0 };
    private static final int[] MP3_MPEG2_LAYER1_BITRATES = { 0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192,
            224, 256, 0 };
    private static final int[] MP3_MPEG2_LAYER23_BITRATES = { 0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128,
            144, 160, 0 };
    private static final int[] MP3_MPEG1_SAMPLE_RATES = { 44100, 48000, 32000, 0 };
    private static final int[] MP3_MPEG2_SAMPLE_RATES = { 22050, 24000, 16000, 0 };
    private static final int[] MP3_MPEG25_SAMPLE_RATES = { 11025, 12000, 8000, 0 };

    private Mp3FrameSync() {
    }

    public static int findFrameSync(byte[] bytes, int length) {
        int safeLength = Math.max(0, Math.min(length, bytes != null ? bytes.length : 0));
        if (bytes == null || safeLength < 4) {
            return -1;
        }
        for (int i = 0; i + 3 < safeLength; i++) {
            Frame first = parseFrame(bytes, i, safeLength);
            if (first == null) {
                continue;
            }

            int pos = i;
            int validFrames = 0;
            while (validFrames < 4) {
                Frame frame = parseFrame(bytes, pos, safeLength);
                if (frame == null || !frame.isCompatibleWith(first)) {
                    break;
                }
                validFrames++;
                pos += frame.frameLength();
            }
            if (validFrames >= 3) {
                return i;
            }
        }
        return -1;
    }

    public static Frame parseFrame(byte[] bytes, int offset, int length) {
        int safeLength = Math.max(0, Math.min(length, bytes != null ? bytes.length : 0));
        if (bytes == null || offset < 0 || offset + 4 > safeLength) {
            return null;
        }
        int b0 = bytes[offset] & 0xFF;
        int b1 = bytes[offset + 1] & 0xFF;
        int b2 = bytes[offset + 2] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) {
            return null;
        }

        int version = (b1 >> 3) & 0x03;
        int layer = (b1 >> 1) & 0x03;
        int bitrateIndex = (b2 >> 4) & 0x0F;
        int sampleRateIndex = (b2 >> 2) & 0x03;
        int padding = (b2 >> 1) & 0x01;
        if (version == 0x01 || layer == 0x00 || bitrateIndex == 0x00 || bitrateIndex == 0x0F
                || sampleRateIndex == 0x03) {
            return null;
        }

        int bitrateKbps = mp3BitrateKbps(version, layer, bitrateIndex);
        int sampleRate = mp3SampleRate(version, sampleRateIndex);
        if (bitrateKbps <= 0 || sampleRate <= 0) {
            return null;
        }

        int frameLength;
        if (layer == 0x03) {
            frameLength = ((12 * bitrateKbps * 1000) / sampleRate + padding) * 4;
        } else if (layer == 0x02) {
            frameLength = (144 * bitrateKbps * 1000) / sampleRate + padding;
        } else {
            int coefficient = version == 0x03 ? 144 : 72;
            frameLength = (coefficient * bitrateKbps * 1000) / sampleRate + padding;
        }
        if (frameLength < 4 || offset + frameLength > safeLength) {
            return null;
        }
        return new Frame(version, layer, sampleRate, frameLength);
    }

    private static int mp3BitrateKbps(int version, int layer, int index) {
        if (version == 0x03) {
            if (layer == 0x03) {
                return MP3_MPEG1_LAYER1_BITRATES[index];
            }
            if (layer == 0x02) {
                return MP3_MPEG1_LAYER2_BITRATES[index];
            }
            return MP3_MPEG1_LAYER3_BITRATES[index];
        }
        if (layer == 0x03) {
            return MP3_MPEG2_LAYER1_BITRATES[index];
        }
        return MP3_MPEG2_LAYER23_BITRATES[index];
    }

    private static int mp3SampleRate(int version, int index) {
        if (version == 0x03) {
            return MP3_MPEG1_SAMPLE_RATES[index];
        }
        if (version == 0x02) {
            return MP3_MPEG2_SAMPLE_RATES[index];
        }
        return MP3_MPEG25_SAMPLE_RATES[index];
    }

    public record Frame(int version, int layer, int sampleRate, int frameLength) {
        boolean isCompatibleWith(Frame other) {
            return version == other.version && layer == other.layer && sampleRate == other.sampleRate;
        }
    }
}
