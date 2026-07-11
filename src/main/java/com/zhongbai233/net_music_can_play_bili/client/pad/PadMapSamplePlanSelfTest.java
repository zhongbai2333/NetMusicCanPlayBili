package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.List;

/** Lightweight self tests for Pad map sampling cell planning. */
public final class PadMapSamplePlanSelfTest {
    private PadMapSamplePlanSelfTest() {
    }

    public static void main(String[] args) {
        skipsAlreadyKnownTiles();
        prioritizesVisibleCells();
        prioritizesPreviewCoreBeforeVisibleRemainder();
        prioritizesVisibleCellsBeforePrefetchBand();
        ordersByTileDistanceThenTileCoordinates();
        preservesNegativeLocalIntraTileOrdering();
        System.out.println("PadMapSamplePlanSelfTest passed");
    }

    private static void skipsAlreadyKnownTiles() {
        PadMapTileKind[] tiles = unknownTiles(3, 1);
        tiles[1] = PadMapTileKind.GRASS;
        List<PadMapSamplePlan.Cell> cells = PadMapSamplePlan.collectPendingCells(0, 0, 1, 3, 1, 3, 1, tiles);
        if (cells.size() != 2 || cells.stream().anyMatch(cell -> cell.index() == 1)) {
            throw new AssertionError("sample plan should skip non-UNKNOWN tiles");
        }
    }

    private static void prioritizesVisibleCells() {
        PadMapTileKind[] tiles = unknownTiles(5, 1);
        List<PadMapSamplePlan.Cell> cells = PadMapSamplePlan.collectPendingCells(0, 0, 1, 5, 1, 1, 1, tiles);
        if (cells.isEmpty() || !cells.get(0).visible() || cells.get(0).worldX() != 0) {
            throw new AssertionError("visible center cell should be sampled first");
        }
        int firstNonVisible = -1;
        for (int i = 0; i < cells.size(); i++) {
            if (!cells.get(i).visible()) {
                firstNonVisible = i;
                break;
            }
        }
        if (firstNonVisible <= 0) {
            throw new AssertionError("non-visible cells should follow visible cells");
        }
    }

    private static void prioritizesPreviewCoreBeforeVisibleRemainder() {
        PadMapTileKind[] tiles = unknownTiles(161, 121);
        List<PadMapSamplePlan.Cell> cells = PadMapSamplePlan.collectPendingCells(0, 0, 1, 161, 121, 97, 65, tiles);
        int coreIndex = indexOf(cells, 0, 0);
        int visibleRemainderIndex = indexOf(cells, 20, 0);
        if (coreIndex < 0 || visibleRemainderIndex < 0) {
            throw new AssertionError("expected core and visible remainder cells in plan");
        }
        if (coreIndex >= visibleRemainderIndex) {
            throw new AssertionError("preview core cells should be sampled before visible remainder cells");
        }
    }

    private static void prioritizesVisibleCellsBeforePrefetchBand() {
        PadMapTileKind[] tiles = unknownTiles(161, 121);
        List<PadMapSamplePlan.Cell> cells = PadMapSamplePlan.collectPendingCells(0, 0, 1, 161, 121, 97, 65, tiles);
        int ordinaryVisibleIndex = indexOf(cells, 20, 0);
        int visibleEdgeIndex = indexOf(cells, 48, 0);
        int prefetchIndex = indexOf(cells, 49, 0);
        if (visibleEdgeIndex < 0 || prefetchIndex < 0 || ordinaryVisibleIndex < 0) {
            throw new AssertionError("expected ordinary, edge, and prefetch cells in plan");
        }
        if (prefetchIndex <= ordinaryVisibleIndex) {
            throw new AssertionError("near-overscan prefetch cells should not starve ordinary visible interior cells");
        }
        if (prefetchIndex <= visibleEdgeIndex) {
            throw new AssertionError("near-overscan prefetch cells should not starve visible edge cells");
        }
    }

    private static void ordersByTileDistanceThenTileCoordinates() {
        PadMapTileKind[] tiles = unknownTiles(65, 1);
        List<PadMapSamplePlan.Cell> cells = PadMapSamplePlan.collectPendingCells(0, 0, 1, 65, 1, 0, 0, tiles);
        PadMapSamplePlan.Cell first = cells.get(0);
        if (first.tileDistance() != 0 || first.tileX() != 0 || first.tileZ() != 0) {
            throw new AssertionError("nearest tile should be sampled first when no cell is visible: " + first);
        }
        PadMapSamplePlan.Cell last = cells.get(cells.size() - 1);
        if (last.tileDistance() < first.tileDistance()) {
            throw new AssertionError("tile distance should be non-decreasing overall");
        }
    }

    private static void preservesNegativeLocalIntraTileOrdering() {
        PadMapTileKind[] tiles = unknownTiles(33, 1);
        List<PadMapSamplePlan.Cell> cells = PadMapSamplePlan.collectPendingCells(0, 0, 1, 33, 1, 0, 0, tiles);
        PadMapSamplePlan.Cell negativeTileCell = cells.stream()
                .filter(cell -> cell.tileX() == -1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected negative tile cell"));
        if (negativeTileCell.intraTileIndex() < 0 || negativeTileCell.intraTileIndex() >= 16 * 16) {
            throw new AssertionError("negative local coordinates should use floorMod for intra tile index");
        }
    }

    private static PadMapTileKind[] unknownTiles(int width, int height) {
        PadMapTileKind[] tiles = new PadMapTileKind[width * height];
        java.util.Arrays.fill(tiles, PadMapTileKind.UNKNOWN);
        return tiles;
    }

    private static int indexOf(List<PadMapSamplePlan.Cell> cells, int worldX, int worldZ) {
        for (int i = 0; i < cells.size(); i++) {
            PadMapSamplePlan.Cell cell = cells.get(i);
            if (cell.worldX() == worldX && cell.worldZ() == worldZ) {
                return i;
            }
        }
        return -1;
    }
}
