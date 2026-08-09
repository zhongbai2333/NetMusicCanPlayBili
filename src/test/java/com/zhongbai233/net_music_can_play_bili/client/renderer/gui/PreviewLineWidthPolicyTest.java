package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewLineWidthPolicyTest {
    @Test
    void perspectiveLinesGrowWithDistanceAndRemainBounded() {
        float near = PreviewLineWidthPolicy.perspective(2.0F);
        float medium = PreviewLineWidthPolicy.perspective(12.0F);
        float far = PreviewLineWidthPolicy.perspective(10_000.0F);

        assertEquals(1.30F, near, 0.001F);
        assertTrue(medium > near);
        assertEquals(4.0F, far, 0.001F);
    }

    @Test
    void orthographicLinesGrowWhenMoreWorldSpaceIsVisible() {
        float close = PreviewLineWidthPolicy.orthographic(2.0F);
        float zoomedOut = PreviewLineWidthPolicy.orthographic(12.0F);

        assertEquals(1.30F, close, 0.001F);
        assertTrue(zoomedOut > close);
    }

    @Test
    void invalidInputsUseSafeBaseline() {
        assertEquals(1.30F, PreviewLineWidthPolicy.perspective(Float.NaN), 0.001F);
        assertEquals(1.30F, PreviewLineWidthPolicy.orthographic(0.0F), 0.001F);
    }
}