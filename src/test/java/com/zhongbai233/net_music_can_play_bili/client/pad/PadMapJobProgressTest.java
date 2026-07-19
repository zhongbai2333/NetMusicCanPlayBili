package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadMapJobProgressTest {
    @Test
    void appliesInitialBurstOnFirstStep() {
        PadMapJobProgress progress = new PadMapJobProgress(100, 10, 35, 2);

        PadMapJobProgress.Step first = progress.beginStep(1);
        PadMapJobProgress.Step second = progress.beginStep(1);

        assertEquals(0, first.startInclusive());
        assertEquals(35, first.endExclusive());
        assertEquals(35, second.startInclusive());
        assertEquals(45, second.endExclusive());
        assertEquals(2, progress.steps());
    }

    @Test
    void publishesFirstPreviewAfterProgressOnlyOnce() {
        PadMapJobProgress progress = new PadMapJobProgress(100, 10, 0, 2);
        assertFalse(progress.shouldPublishPreview());
        progress.beginStep(1);
        assertTrue(progress.shouldPublishPreview());
        progress.markPreviewPublished();
        assertFalse(progress.shouldPublishPreview());
    }

    @Test
    void waitsForPreviewIntervalAfterFirstPreview() {
        PadMapJobProgress progress = new PadMapJobProgress(100, 10, 0, 2);
        progress.beginStep(1);
        progress.markPreviewPublished();
        progress.beginStep(1);
        assertFalse(progress.shouldPublishPreview());
        progress.beginStep(1);
        assertTrue(progress.shouldPublishPreview());
    }

    @Test
    void reportsDoneAndPercent() {
        PadMapJobProgress progress = new PadMapJobProgress(25, 10, 0, 1);
        progress.beginStep(3);
        assertTrue(progress.done());
        assertEquals(25, progress.doneCells());
        assertEquals(100, progress.percent());

        PadMapJobProgress empty = new PadMapJobProgress(0, 10, 0, 1);
        assertTrue(empty.done());
        assertEquals(100, empty.percent());
    }
}