package com.zhongbai233.net_music_can_play_bili.client.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlaybackRangeDebugLayoutTest {
    @Test
    void wrapsLongDiagnosticFieldsWithinPanelWidth() {
        List<String> lines = PlaybackRangeDebugRenderer.wrapText(
                "source=cef74d4de-b2c session=824633765 state=PLAYING route=外设独占", 24,
                value -> value.length());

        assertTrue(lines.size() >= 3);
        assertTrue(lines.stream().allMatch(line -> line.length() <= 24));
        assertEquals("source=cef74d4de-b2c", lines.get(0));
    }

    @Test
    void lifecycleBarAdvancesAcrossThreeStableStages() {
        assertEquals(1, PlaybackRangeDebugRenderer.lifecycleStage("METADATA"));
        assertEquals(2, PlaybackRangeDebugRenderer.lifecycleStage("STARTING"));
        assertEquals(3, PlaybackRangeDebugRenderer.lifecycleStage("PLAYING"));
        assertEquals(0, PlaybackRangeDebugRenderer.lifecycleStage("FAILED"));
    }

    @Test
    void boostedVolumeUsesSecondHalfOfMeter() {
        assertEquals(0.25F, PlaybackRangeDebugRenderer.volumeBarProgress(0.5F), 1.0e-6F);
        assertEquals(0.5F, PlaybackRangeDebugRenderer.volumeBarProgress(1.0F), 1.0e-6F);
        assertEquals(0.75F, PlaybackRangeDebugRenderer.volumeBarProgress(1.5F), 1.0e-6F);
        assertEquals(1.0F, PlaybackRangeDebugRenderer.volumeBarProgress(3.0F), 1.0e-6F);
    }

    @Test
    void playbackSyncMeterDropsAsAudibleVisualDriftGrows() {
        assertEquals(1.0F, PlaybackRangeDebugRenderer.syncBarProgress(10_000L, 10_000L), 1.0e-6F);
        assertEquals(0.75F, PlaybackRangeDebugRenderer.syncBarProgress(10_000L, 10_500L), 1.0e-6F);
        assertEquals(0.0F, PlaybackRangeDebugRenderer.syncBarProgress(10_000L, 12_500L), 1.0e-6F);
    }

    @Test
    void bufferedAudioLeadIsClassifiedSeparatelyFromPlaybackDrift() {
        assertEquals(500L, PlaybackRangeDebugRenderer.bufferLeadMillis(10_000L, 10_500L));
        assertEquals(1_300L, PlaybackRangeDebugRenderer.bufferLeadMillis(10_000L, 11_300L));
        assertEquals("正常", PlaybackRangeDebugRenderer.bufferHealth(500L));
        assertEquals("正常", PlaybackRangeDebugRenderer.bufferHealth(1_300L));
        assertEquals("偏高", PlaybackRangeDebugRenderer.bufferHealth(1_800L));
        assertEquals("过深", PlaybackRangeDebugRenderer.bufferHealth(2_100L));
        assertEquals(-1L, PlaybackRangeDebugRenderer.bufferLeadMillis(-1L, 10_000L));
    }

    @Test
    void panelBudgetKeepsCenterViewClearAtLargeGuiScale() {
        assertEquals(211, DebugHudLayout.widthBudget(480, false, 5));
        assertEquals(182, DebugHudLayout.widthBudget(480, true, 5));
    }

    @Test
    void smallScreenPanelScalesWithoutRemovingContent() {
        DebugHudLayout.Plan plan = DebugHudLayout.plan(480, 240, false, 430, 384, 5);

        assertTrue(plan.visible());
        assertTrue(plan.scale() < 1.0F);
        assertTrue(plan.renderedWidth() <= Math.floor(480 * DebugHudLayout.SINGLE_PANEL_WIDTH_RATIO));
        assertTrue(plan.renderedHeight() <= Math.floor(240 * DebugHudLayout.PANEL_HEIGHT_RATIO));
    }

    @Test
    void proportionalScalePreservesPanelAspectRatio() {
        DebugHudLayout.Plan plan = DebugHudLayout.plan(480, 240, true, 430, 332, 5);

        assertEquals(430.0F / 332.0F,
                plan.renderedWidth() / plan.renderedHeight(), 1.0e-5F);
    }

    @Test
    void largeScreenRetainsNominalScale() {
        DebugHudLayout.Plan plan = DebugHudLayout.plan(1920, 1080, false, 430, 384, 5);

        assertEquals(1.0F, plan.scale(), 1.0e-6F);
        assertEquals(430.0F, plan.renderedWidth(), 1.0e-6F);
        assertEquals(384.0F, plan.renderedHeight(), 1.0e-6F);
    }

    @Test
    void debugModesKeepHudAndWorldRangesIndependent() {
        assertTrue(PlaybackDebugMode.UI.hudEnabled());
        assertTrue(!PlaybackDebugMode.UI.rangeEnabled());
        assertTrue(!PlaybackDebugMode.RANGE.hudEnabled());
        assertTrue(PlaybackDebugMode.RANGE.rangeEnabled());
        assertTrue(PlaybackDebugMode.BOTH.hudEnabled());
        assertTrue(PlaybackDebugMode.BOTH.rangeEnabled());
        assertTrue(!PlaybackDebugMode.OFF.enabled());
    }

    @Test
    void videoSyncDeltaIsSignedAndHandlesMissingSamples() {
        assertEquals("+250ms", VideoPlaybackDebugRenderer.signedDelta(1_250L, 1_000L));
        assertEquals("-250ms", VideoPlaybackDebugRenderer.signedDelta(750L, 1_000L));
        assertEquals("-", VideoPlaybackDebugRenderer.signedDelta(-1L, 1_000L));
    }
}
