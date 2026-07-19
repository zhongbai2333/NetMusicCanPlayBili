package com.zhongbai233.net_music_can_play_bili.media.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PcmPlanarConverterTest {
    @Test
    void converts16BitLittleEndianSlice() {
        byte[] pcm = { 99, 98, 0x00, (byte) 0x80, (byte) 0xFF, 0x7F, 97 };
        AudioFormat format = new AudioFormat(48_000, 16, 2, true, false);

        float[][] planar = PcmPlanarConverter.convert(pcm, 2, 4, format);

        assertEquals(-1.0f, planar[0][0], 1.0e-6f);
        assertEquals(32767.0f / 32768.0f, planar[1][0], 1.0e-6f);
    }

    @Test
    void converts24BitLittleEndianSlice() {
        byte[] pcm = { 11, 22, 0x00, 0x00, (byte) 0x80, (byte) 0xFF, (byte) 0xFF, 0x7F, 33 };
        AudioFormat format = new AudioFormat(48_000, 24, 2, true, false);

        float[][] planar = PcmPlanarConverter.convert(pcm, 2, 6, format);

        assertEquals(-1.0f, planar[0][0], 1.0e-6f);
        assertEquals(8388607.0f / 8388608.0f, planar[1][0], 1.0e-6f);
    }

    @Test
    void converts32BitBigEndianSlice() {
        byte[] pcm = { 44, (byte) 0x80, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 55 };
        AudioFormat format = new AudioFormat(48_000, 32, 2, true, true);

        float[][] planar = PcmPlanarConverter.convert(pcm, 1, 8, format);

        assertEquals(-1.0f, planar[0][0], 1.0e-6f);
        assertEquals(0.5f, planar[1][0], 1.0e-6f);
    }

    @Test
    void rejectsOutOfRangeSlice() {
        byte[] pcm = { 99, 98, 0x00, (byte) 0x80, (byte) 0xFF, 0x7F, 97 };
        AudioFormat format = new AudioFormat(48_000, 16, 2, true, false);

        assertThrows(IndexOutOfBoundsException.class,
                () -> PcmPlanarConverter.convert(pcm, 2, pcm.length, format));
    }
}