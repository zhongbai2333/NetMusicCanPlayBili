package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadMapSamplePlanTest {
    @Test
    void skipsAlreadyKnownTiles() {
        PadMapTileKind[] tiles = unknownTiles(3, 1);
        tiles[1] = PadMapTileKind.GRASS;
        List<PadMapSamplePlan.Cell> cells = PadMapSamplePlan.collectPendingCells(0, 0, 1, 3, 1, 3, 1, tiles);
        assertEquals(2, cells.size());
        assertFalse(cells.stream().anyMatch(cell -> cell.index() == 1));
    }

    @Test
    void prioritizesVisibleCells() {
        List<PadMapSamplePlan.Cell> cells = PadMapSamplePlan.collectPendingCells(
                0, 0, 1, 5, 1, 1, 1, unknownTiles(5, 1));
        assertFalse(cells.isEmpty());
        assertTrue(cells.getFirst().visible());
        assertEquals(0, cells.getFirst().worldX());
        assertTrue(cells.stream().anyMatch(cell -> !cell.visible()));
    }

    @Test
    void prioritizesPreviewCoreBeforeVisibleRemainder() {
        List<PadMapSamplePlan.Cell> cells = largePlan();
        int coreIndex = indexOf(cells, 0, 0);
        int visibleRemainderIndex = indexOf(cells, 20, 0);
        assertTrue(coreIndex >= 0);
        assertTrue(visibleRemainderIndex >= 0);
        assertTrue(coreIndex < visibleRemainderIndex);
    }

    @Test
    void prioritizesVisibleCellsBeforePrefetchBand() {
        List<PadMapSamplePlan.Cell> cells = largePlan();
        int ordinaryVisibleIndex = indexOf(cells, 20, 0);
        int visibleEdgeIndex = indexOf(cells, 48, 0);
        int prefetchIndex = indexOf(cells, 49, 0);
        assertTrue(ordinaryVisibleIndex >= 0);
        assertTrue(visibleEdgeIndex >= 0);
        assertTrue(prefetchIndex > ordinaryVisibleIndex);
        assertTrue(prefetchIndex > visibleEdgeIndex);
    }

    @Test
    void ordersByTileDistanceThenTileCoordinates() {
        List<PadMapSamplePlan.Cell> cells = PadMapSamplePlan.collectPendingCells(
                0, 0, 1, 65, 1, 0, 0, unknownTiles(65, 1));
        PadMapSamplePlan.Cell first = cells.getFirst();
        PadMapSamplePlan.Cell last = cells.getLast();
        assertEquals(0, first.tileDistance());
        assertEquals(0, first.tileX());
        assertEquals(0, first.tileZ());
        assertTrue(last.tileDistance() >= first.tileDistance());
    }

    @Test
    void preservesNegativeLocalIntraTileOrdering() {
        List<PadMapSamplePlan.Cell> cells = PadMapSamplePlan.collectPendingCells(
                0, 0, 1, 33, 1, 0, 0, unknownTiles(33, 1));
        PadMapSamplePlan.Cell negativeTileCell = cells.stream()
                .filter(cell -> cell.tileX() == -1)
                .findFirst()
                .orElseThrow();
        assertTrue(negativeTileCell.intraTileIndex() >= 0);
        assertTrue(negativeTileCell.intraTileIndex() < 16 * 16);
    }

    private static List<PadMapSamplePlan.Cell> largePlan() {
        return PadMapSamplePlan.collectPendingCells(0, 0, 1, 161, 121, 97, 65, unknownTiles(161, 121));
    }

    private static PadMapTileKind[] unknownTiles(int width, int height) {
        PadMapTileKind[] tiles = new PadMapTileKind[width * height];
        Arrays.fill(tiles, PadMapTileKind.UNKNOWN);
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