package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PcmFadeInAudioInputStreamTest {
    @Test
    void fadesSeekedPcmThenPreservesSamples() throws IOException {
        AudioFormat format = new AudioFormat(1_000, 16, 1, true, false);
        byte[] pcm = constantPcm(1000, 100);
        AudioInputStream source = new AudioInputStream(new ByteArrayInputStream(pcm), format, 100);

        AudioInputStream faded = PcmFadeInAudioInputStream.wrap(source, 80);
        byte[] actual = faded.readAllBytes();

        assertEquals(0, sample(actual, 0));
        assertEquals(500, sample(actual, 40));
        assertEquals(988, sample(actual, 79));
        assertEquals(1000, sample(actual, 80));
        assertEquals(1000, sample(actual, 99));
    }

    @Test
    void bypassesUnsupportedPcmFormat() {
        AudioFormat eightBit = new AudioFormat(44_100, 8, 1, true, false);
        AudioInputStream source = new AudioInputStream(new ByteArrayInputStream(new byte[16]), eightBit, 16);

        assertSame(source, PcmFadeInAudioInputStream.wrap(source, 80));
    }

    @Test
    void readWithOffsetDoesNotTouchSurroundingBytes() throws IOException {
        AudioFormat format = new AudioFormat(1_000, 16, 1, true, false);
        AudioInputStream source = new AudioInputStream(new ByteArrayInputStream(constantPcm(1000, 1)), format, 1);
        AudioInputStream faded = PcmFadeInAudioInputStream.wrap(source, 80);
        byte[] target = { 11, 22, 33, 44, 55, 66 };

        assertEquals(2, faded.read(target, 2, 2));
        assertArrayEquals(new byte[] { 11, 22, 0, 0, 55, 66 }, target);
    }

    private static byte[] constantPcm(int sample, int frames) {
        byte[] bytes = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            bytes[i * 2] = (byte) sample;
            bytes[i * 2 + 1] = (byte) (sample >> 8);
        }
        return bytes;
    }

    private static short sample(byte[] bytes, int frame) {
        int offset = frame * 2;
        return (short) ((bytes[offset] & 0xFF) | (bytes[offset + 1] << 8));
    }
}