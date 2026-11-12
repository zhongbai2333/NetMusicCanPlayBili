package com.zhongbai233.net_music_can_play_bili.bili;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Decoder framing, PCM seek fades and bounded signal-quality observations. */
class AudioSignalQualityTest {
    @Test
    void findsSyncAfterNoise() {
        byte[] stream = new byte[13 + 417 * 3];
        for (int i = 0; i < 13; i++) {
            stream[i] = (byte) (0x40 + i);
        }
        for (int offset = 13; offset < stream.length; offset += 417) {
            writeMpeg1Layer3FrameHeader(stream, offset);
        }

        assertEquals(13, Mp3FrameSync.findFrameSync(stream, stream.length));
    }

    @Test
    void rejectsInvalidAndTruncatedFrames() {
        byte[] invalid = { 0x00, 0x11, 0x22, 0x33, 0x44 };
        assertEquals(-1, Mp3FrameSync.findFrameSync(invalid, invalid.length));
        assertNull(Mp3FrameSync.parseFrame(invalid, 0, invalid.length));

        byte[] truncatedHeader = { (byte) 0xFF, (byte) 0xFB, (byte) 0x90 };
        assertEquals(-1, Mp3FrameSync.findFrameSync(truncatedHeader, truncatedHeader.length));
    }

    @Test
    void rejectsIsolatedFalseSync() {
        byte[] stream = new byte[2048];
        writeMpeg1Layer3FrameHeader(stream, 7);

        assertEquals(-1, Mp3FrameSync.findFrameSync(stream, stream.length));
    }

    private static void writeMpeg1Layer3FrameHeader(byte[] target, int offset) {
        target[offset] = (byte) 0xFF;
        target[offset + 1] = (byte) 0xFB;
        target[offset + 2] = (byte) 0x90;
        target[offset + 3] = 0x64;
    }

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

    @Test
    void measuresFinitePcmWithoutRetainingSamples() {
        StereoOpenALHandler.PcmQuality quality = StereoOpenALHandler.PcmQuality.measure(new float[][] {
                { 0.0F, 0.5F, -1.0F, Float.NaN },
                { 0.25F, -0.25F }
        });

        assertEquals(5L, quality.samples());
        assertEquals(1.0F, quality.peak());
        assertEquals(Math.sqrt(1.375D / 5.0D), quality.rms(), 1.0E-12D);
        assertEquals(0.2D, quality.clippedRatio(), 1.0E-12D);
    }

    @Test
    void nullAndEmptyInputsProduceEmptyQuality() {
        assertEquals(new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D),
                StereoOpenALHandler.PcmQuality.measure(null));
        assertEquals(new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D),
                StereoOpenALHandler.PcmQuality.measure(new float[][] { null }));
    }

    @Test
    void boundedWindowAccumulatesSmallDecoderReads() {
        StereoOpenALHandler.PcmQualityWindow window = new StereoOpenALHandler.PcmQualityWindow(4L);

        assertEquals(new StereoOpenALHandler.PcmQuality(2L, 0.0F, 0.0D, 0.0D),
                window.observe(new float[][] { { 0.0F }, { 0.0F } }));
        StereoOpenALHandler.PcmQuality quality = window.observe(new float[][] {
                { 0.5F, 1.0F },
                { 0.25F, -0.25F }
        });

        assertEquals(4L, quality.samples());
        assertEquals(1.0F, quality.peak());
        assertEquals(Math.sqrt(1.25D / 4.0D), quality.rms(), 1.0E-12D);
        assertEquals(0.25D, quality.clippedRatio(), 1.0E-12D);
        assertEquals(quality, window.observe(new float[][] { { -0.75F } }));
    }

    @Test
    void audibleWindowSkipsCodecPrimingSilenceBeforeCapturing() {
        StereoOpenALHandler.AudiblePcmQualityWindow window =
                new StereoOpenALHandler.AudiblePcmQualityWindow(4L, 0.001F);

        assertEquals(new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D),
                window.observe(new float[][] { { 0.0F, 0.0F }, { 0.0F, 0.0F } }));
        StereoOpenALHandler.PcmQuality audible = window.observe(new float[][] {
                { 0.25F, -0.25F }, { 0.5F, -0.5F }
        });

        assertEquals(4L, audible.samples());
        assertEquals(0.5F, audible.peak());
        assertEquals(Math.sqrt(0.625D / 4.0D), audible.rms(), 1.0E-12D);
        assertEquals(audible, window.observe(new float[][] { { 1.0F } }));
    }
}
