package com.zhongbai233.net_music_can_play_bili.media.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import org.junit.jupiter.api.Test;

class PcmStartupSeekPolicyTest {
    private static final AudioFormat STEREO_16BIT_1KHZ = new AudioFormat(1_000.0F, 16, 2, true, false);

    @Test
    void skipsFixedOffsetAtWholeFrameBoundary() throws Exception {
        AudioInputStream stream = streamWithBytes(4_000);

        long skipped = PcmStartupSeekPolicy.skipFixedOffset(stream, STEREO_16BIT_1KHZ, 0.251F);

        assertEquals(1_004L, skipped);
        assertEquals(2_996, stream.available());
    }

    @Test
    void zeroAndNegativeOffsetsDoNotConsumePcm() throws Exception {
        AudioInputStream zero = streamWithBytes(400);
        AudioInputStream negative = streamWithBytes(400);

        assertEquals(0L, PcmStartupSeekPolicy.skipFixedOffset(zero, STEREO_16BIT_1KHZ, 0.0F));
        assertEquals(0L, PcmStartupSeekPolicy.skipFixedOffset(negative, STEREO_16BIT_1KHZ, -1.0F));
        assertEquals(400, zero.available());
        assertEquals(400, negative.available());
    }

    @Test
    void shortStreamStopsAtAvailableWholeFrames() throws Exception {
        AudioInputStream stream = streamWithBytes(400);

        long skipped = PcmStartupSeekPolicy.skipFixedOffset(stream, STEREO_16BIT_1KHZ, 1.0F);

        assertEquals(400L, skipped);
        assertEquals(0, stream.available());
    }

    private static AudioInputStream streamWithBytes(int bytes) {
        return new AudioInputStream(new ByteArrayInputStream(new byte[bytes]), STEREO_16BIT_1KHZ,
                bytes / STEREO_16BIT_1KHZ.getFrameSize());
    }
}