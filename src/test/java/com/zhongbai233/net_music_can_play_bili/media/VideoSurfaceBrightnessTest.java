package com.zhongbai233.net_music_can_play_bili.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoSurfaceBrightnessTest {
    @Test
    void normalizesTheSupportedRangeAndMalformedValues() {
        assertEquals(1.0F, VideoSurfaceBrightness.normalize(Float.NaN));
        assertEquals(1.0F, VideoSurfaceBrightness.normalize(Float.POSITIVE_INFINITY));
        assertEquals(0.0F, VideoSurfaceBrightness.normalize(Float.NEGATIVE_INFINITY));
        assertEquals(0.0F, VideoSurfaceBrightness.normalize(-0.1F));
        assertEquals(0.4F, VideoSurfaceBrightness.normalize(0.4F));
        assertEquals(1.0F, VideoSurfaceBrightness.normalize(1.1F));
    }

    @Test
    void vertexColorDarkensRgbWithoutChangingOpacitySemantics() {
        assertEquals(0x80808080, VideoSurfaceBrightness.vertexColor(0.5F, 0.5F));
        assertEquals(0xFF000000, VideoSurfaceBrightness.vertexColor(0.0F, 1.0F));
        assertEquals(0xFFFFFFFF, VideoSurfaceBrightness.vertexColor(1.0F, 1.0F));
    }
}
