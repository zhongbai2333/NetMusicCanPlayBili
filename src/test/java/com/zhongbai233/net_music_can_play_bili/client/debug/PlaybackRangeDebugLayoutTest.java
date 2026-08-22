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
}
