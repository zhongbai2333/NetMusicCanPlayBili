package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.List;

/** Lightweight self tests for dirty map invalidation calculations. */
public final class PadMapDirtyInvalidationSelfTest {
    private PadMapDirtyInvalidationSelfTest() {
    }

    public static void main(String[] args) {
        detectsDirtyChunkTouchingSnapshot();
        ignoresDirtyChunkOutsideSnapshot();
        mapsDirtyChunkToCellRange();
        mapsNegativeDirtyChunkToCellRangeWithFloorDiv();
        invalidatesOnlyDirtySnapshotCells();
        invalidatesNegativeDirtySnapshotCells();
        limitsWorkToDirtyIntersection();
        System.out.println("PadMapDirtyInvalidationSelfTest passed");
    }

    private static void detectsDirtyChunkTouchingSnapshot() {
        PadMapSnapshot snapshot = snapshot(0, 0, 4, 8, 8);
        List<PadMapDirtyChunkTracker.Key> dirty = List.of(new PadMapDirtyChunkTracker.Key("overworld", 0, 0));
        if (!PadMapDirtyInvalidation.touchesSnapshot(snapshot, 4, dirty)) {
            throw new AssertionError("chunk overlapping snapshot bounds should invalidate snapshot");
        }
    }

    private static void ignoresDirtyChunkOutsideSnapshot() {
        PadMapSnapshot snapshot = snapshot(0, 0, 4, 8, 8);
        List<PadMapDirtyChunkTracker.Key> dirty = List.of(new PadMapDirtyChunkTracker.Key("overworld", 10, 10));
        if (PadMapDirtyInvalidation.touchesSnapshot(snapshot, 4, dirty)) {
            throw new AssertionError("far chunk should not invalidate snapshot");
        }
    }

    private static void mapsDirtyChunkToCellRange() {
        List<PadMapDirtyChunkTracker.Key> dirty = List.of(new PadMapDirtyChunkTracker.Key("overworld", 1, 2));
        List<PadMapDirtyInvalidation.CellRange> ranges = PadMapDirtyInvalidation.cellRanges(dirty, 4);
        if (ranges.size() != 1) {
            throw new AssertionError("one dirty chunk should map to one cell range");
        }
        PadMapDirtyInvalidation.CellRange range = ranges.get(0);
        if (range.minCellX() != 4 || range.maxCellX() != 7 || range.minCellZ() != 8 || range.maxCellZ() != 11) {
            throw new AssertionError("unexpected positive chunk cell range: " + range);
        }
        if (!range.contains(5, 9) || range.contains(8, 9)) {
            throw new AssertionError("cell range containment should be inclusive and bounded");
        }
    }

    private static void mapsNegativeDirtyChunkToCellRangeWithFloorDiv() {
        List<PadMapDirtyChunkTracker.Key> dirty = List.of(new PadMapDirtyChunkTracker.Key("overworld", -1, -1));
        List<PadMapDirtyInvalidation.CellRange> ranges = PadMapDirtyInvalidation.cellRanges(dirty, 6);
        PadMapDirtyInvalidation.CellRange range = ranges.get(0);
        if (range.minCellX() != -3 || range.maxCellX() != -1 || range.minCellZ() != -3 || range.maxCellZ() != -1) {
            throw new AssertionError("negative chunk range must use floorDiv semantics: " + range);
        }
    }

    private static void invalidatesOnlyDirtySnapshotCells() {
        PadMapSnapshot original = filledSnapshot(16, 16, 1, 32, 32, PadMapTileKind.GRASS);
        List<PadMapDirtyChunkTracker.Key> dirty = List.of(new PadMapDirtyChunkTracker.Key("overworld", 0, 0));
        PadMapSnapshot invalidated = PadMapDirtyInvalidation.invalidateSnapshot(original, 1, dirty);
        if (invalidated == null) {
            throw new AssertionError("overlapping dirty chunk should produce an invalidated seed");
        }
        if (invalidated.tile(16, 16) != PadMapTileKind.GRASS) {
            throw new AssertionError("cell outside dirty chunk must retain cached tile");
        }
        if (invalidated.tile(0, 0) != PadMapTileKind.UNKNOWN
            || invalidated.tile(15, 15) != PadMapTileKind.UNKNOWN) {
            throw new AssertionError("dirty chunk cells must become UNKNOWN");
        }
        long unknown = java.util.Arrays.stream(invalidated.tiles())
                .filter(kind -> kind == PadMapTileKind.UNKNOWN)
                .count();
        if (unknown != 16L * 16L) {
            throw new AssertionError("only one chunk should be scheduled again, unknown=" + unknown);
        }
    }

    private static void invalidatesNegativeDirtySnapshotCells() {
        PadMapSnapshot original = filledSnapshot(0, 0, 1, 32, 32, PadMapTileKind.WATER);
        List<PadMapDirtyChunkTracker.Key> dirty = List.of(new PadMapDirtyChunkTracker.Key("overworld", -1, -1));
        PadMapSnapshot invalidated = PadMapDirtyInvalidation.invalidateSnapshot(original, 1, dirty);
        if (invalidated == null || invalidated.tile(0, 0) != PadMapTileKind.UNKNOWN
                || invalidated.tile(16, 16) != PadMapTileKind.WATER) {
            throw new AssertionError("negative dirty chunk should invalidate only its mapped snapshot quadrant");
        }
    }

    private static void limitsWorkToDirtyIntersection() {
        PadMapSnapshot large = filledSnapshot(0, 0, 1, 576, 384, PadMapTileKind.GRASS);
        List<PadMapDirtyChunkTracker.Key> dirty = List.of(new PadMapDirtyChunkTracker.Key("overworld", 0, 0));
        int affected = PadMapDirtyInvalidation.affectedCellCount(large, 1, dirty);
        if (affected != 16 * 16) {
            throw new AssertionError("one dirty chunk should touch 256 cells, not scan the full snapshot: " + affected);
        }
    }

    private static PadMapSnapshot snapshot(int centerX, int centerZ, int cellSize, int width, int height) {
        return new PadMapSnapshot(centerX, 64, centerZ, cellSize, width, height,
                new PadMapTileKind[width * height], 1.0F);
    }

    private static PadMapSnapshot filledSnapshot(int centerX, int centerZ, int cellSize, int width, int height,
            PadMapTileKind kind) {
        PadMapTileKind[] tiles = new PadMapTileKind[width * height];
        java.util.Arrays.fill(tiles, kind);
        return new PadMapSnapshot(centerX, 64, centerZ, cellSize, width, height, tiles, 1.0F);
    }
}
