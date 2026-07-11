package com.zhongbai233.net_music_can_play_bili.client.pad;

/** Lightweight self tests for Pad map job progress tracking. */
public final class PadMapJobProgressSelfTest {
    private PadMapJobProgressSelfTest() {
    }

    public static void main(String[] args) {
        appliesInitialBurstOnFirstStep();
        publishesFirstPreviewAfterProgressOnlyOnce();
        waitsForPreviewIntervalAfterFirstPreview();
        reportsDoneAndPercent();
        System.out.println("PadMapJobProgressSelfTest passed");
    }

    private static void appliesInitialBurstOnFirstStep() {
        PadMapJobProgress progress = new PadMapJobProgress(100, 10, 35, 2);
        PadMapJobProgress.Step first = progress.beginStep(1);
        if (first.startInclusive() != 0 || first.endExclusive() != 35 || progress.steps() != 1) {
            throw new AssertionError("first step should use initial burst when larger than chunk budget");
        }
        PadMapJobProgress.Step second = progress.beginStep(1);
        if (second.startInclusive() != 35 || second.endExclusive() != 45 || progress.steps() != 2) {
            throw new AssertionError("second step should use normal chunk budget");
        }
    }

    private static void publishesFirstPreviewAfterProgressOnlyOnce() {
        PadMapJobProgress progress = new PadMapJobProgress(100, 10, 0, 2);
        if (progress.shouldPublishPreview()) {
            throw new AssertionError("no preview before any progress");
        }
        progress.beginStep(1);
        if (!progress.shouldPublishPreview()) {
            throw new AssertionError("first progress should publish preview");
        }
        progress.markPreviewPublished();
        if (progress.shouldPublishPreview()) {
            throw new AssertionError("same cursor should not publish duplicate preview");
        }
    }

    private static void waitsForPreviewIntervalAfterFirstPreview() {
        PadMapJobProgress progress = new PadMapJobProgress(100, 10, 0, 2);
        progress.beginStep(1);
        progress.markPreviewPublished();
        progress.beginStep(1);
        if (progress.shouldPublishPreview()) {
            throw new AssertionError("preview interval should wait for previewChunks * cellsPerChunkBudget");
        }
        progress.beginStep(1);
        if (!progress.shouldPublishPreview()) {
            throw new AssertionError("preview should publish after interval is reached");
        }
    }

    private static void reportsDoneAndPercent() {
        PadMapJobProgress progress = new PadMapJobProgress(25, 10, 0, 1);
        progress.beginStep(3);
        if (!progress.done() || progress.doneCells() != 25 || progress.percent() != 100) {
            throw new AssertionError("progress should clamp to total and report completion");
        }
        PadMapJobProgress empty = new PadMapJobProgress(0, 10, 0, 1);
        if (!empty.done() || empty.percent() != 100) {
            throw new AssertionError("empty progress should be complete");
        }
    }
}
