package com.zhongbai233.net_music_can_play_bili.client.pad;

/**
 * Tracks incremental Pad map sampling progress and preview publishing cadence.
 */
final class PadMapJobProgress {
    private final int totalCells;
    private final int cellsPerChunkBudget;
    private final int initialBurstCells;
    private final int previewChunks;
    private int cursor;
    private int lastPreviewCursor;
    private int steps;

    PadMapJobProgress(int totalCells, int cellsPerChunkBudget, int initialBurstCells, int previewChunks) {
        this.totalCells = Math.max(0, totalCells);
        this.cellsPerChunkBudget = Math.max(1, cellsPerChunkBudget);
        this.initialBurstCells = Math.max(0, initialBurstCells);
        this.previewChunks = Math.max(1, previewChunks);
    }

    Step beginStep(int chunkBudget) {
        steps++;
        int cellsPerStep = Math.max(1, chunkBudget) * cellsPerChunkBudget;
        if (steps == 1) {
            cellsPerStep = Math.max(cellsPerStep, initialBurstCells);
        }
        int start = cursor;
        int end = Math.min(totalCells, cursor + cellsPerStep);
        cursor = end;
        return new Step(start, end);
    }

    boolean done() {
        return cursor >= totalCells;
    }

    boolean shouldPublishPreview() {
        if (cursor <= 0 || cursor == lastPreviewCursor) {
            return false;
        }
        if (lastPreviewCursor == 0) {
            return true;
        }
        return cursor - lastPreviewCursor >= previewChunks * cellsPerChunkBudget;
    }

    void markPreviewPublished() {
        lastPreviewCursor = cursor;
    }

    int steps() {
        return steps;
    }

    int doneCells() {
        return Math.min(cursor, totalCells);
    }

    int totalCells() {
        return totalCells;
    }

    int percent() {
        return totalCells <= 0 ? 100 : Math.round(doneCells() * 100.0F / totalCells);
    }

    record Step(int startInclusive, int endExclusive) {
    }
}
