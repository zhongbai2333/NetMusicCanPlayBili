package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StereoOpenALHandlerPcmQualityTest {
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
