package com.zhongbai233.net_music_can_play_bili.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PadRenderPropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsPreserveExistingRenderingBehavior() {
        PadRenderProperties.Offscreen offscreen = PadRenderProperties.offscreen();
        assertEquals(2, offscreen.scale());
        assertEquals(0.5F, offscreen.mapPanRenderBlocks());
        assertEquals(4.0F, offscreen.mapYawRenderDegrees());
        assertEquals(20, offscreen.playbackRefreshTicks());
        assertEquals(60L, offscreen.maxFps());

        PadRenderProperties.MapLayer mapLayer = PadRenderProperties.mapLayer();
        assertEquals(1, mapLayer.cellPixels());
        assertEquals(1, mapLayer.scale());
        assertEquals(500L, mapLayer.minBakeIntervalMillis());
        assertEquals(5, mapLayer.tickIntervalTicks());

        PadRenderProperties.Performance performance = PadRenderProperties.performance();
        assertFalse(performance.explicitEnabled());
        assertEquals(12L, performance.slowWarnMillis());
        assertEquals(5000L, performance.slowWarnCooldownMillis());
        assertEquals(0.05F, PadRenderProperties.handheldLeftShift());
        assertFalse(PadRenderProperties.videoRenderdocProbeEnabled());
    }

    @Test
    void currentOffscreenScaleTakesPrecedenceOverLegacyMp4Scale() {
        set(PadRenderProperties.LEGACY_MP4_OFFSCREEN_SCALE, "3");
        assertEquals(3, PadRenderProperties.offscreen().scale());

        set(PadRenderProperties.OFFSCREEN_SCALE, "4");
        assertEquals(4, PadRenderProperties.offscreen().scale());
    }

    @Test
    void invalidValuesFallBackAndExistingMinimumsRemainStable() {
        set(PadRenderProperties.OFFSCREEN_SCALE, "invalid");
        set(PadRenderProperties.LEGACY_MP4_OFFSCREEN_SCALE, "3");
        set(PadRenderProperties.GUI_PAN_RENDER_BLOCKS, "NaN");
        set(PadRenderProperties.GUI_YAW_RENDER_DEGREES, "-2.0");
        set(PadRenderProperties.GUI_PLAYBACK_REFRESH_TICKS, "0");
        set(PadRenderProperties.MAP_LAYER_TICK_INTERVAL_TICKS, "-1");
        set(PadRenderProperties.HANDHELD_LEFT_SHIFT, "Infinity");
        set(PadRenderProperties.PERF_LOG, "invalid");

        PadRenderProperties.Offscreen offscreen = PadRenderProperties.offscreen();
        assertEquals(3, offscreen.scale());
        assertEquals(0.5F, offscreen.mapPanRenderBlocks());
        assertEquals(0.5F, offscreen.mapYawRenderDegrees());
        assertEquals(1, offscreen.playbackRefreshTicks());
        assertEquals(1, PadRenderProperties.mapLayer().tickIntervalTicks());
        assertEquals(0.05F, PadRenderProperties.handheldLeftShift());
        assertFalse(PadRenderProperties.performance().explicitEnabled());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
