package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Coarse terrain cells preserve alignment, color and representative material. */
class TerrainAggregationTest {
    @Test
    void averagesEveryMapColorInsideAFixedCell() {
        var cells = TerrainMapColorAggregator.aggregate(List.of(
                new TerrainMapColorAggregator.Sample<>(0, 0, 0, "red", 0xFF0000),
                new TerrainMapColorAggregator.Sample<>(1, 1, 1, "blue", 0x0000FF)), 4);

        assertEquals(1, cells.size());
        assertEquals(4, cells.getFirst().size());
        assertEquals(0xFF800080, cells.getFirst().color());
    }

    @Test
    void keepsFourAndEightCubeOriginsAlignedToTheSectionGrid() {
        var samples = List.of(
                new TerrainMapColorAggregator.Sample<>(7, 5, 3, "grass", 0x70A040),
                new TerrainMapColorAggregator.Sample<>(12, 11, 9, "stone", 0x707070));

        var four = TerrainMapColorAggregator.aggregate(samples, 4);
        assertEquals(2, four.size());
        assertEquals(4, four.get(0).localX());
        assertEquals(4, four.get(0).localY());
        assertEquals(0, four.get(0).localZ());
        assertEquals(12, four.get(1).localX());
        assertEquals(8, four.get(1).localY());
        assertEquals(8, four.get(1).localZ());

        var eight = TerrainMapColorAggregator.aggregate(samples, 8);
        assertEquals(2, eight.size());
        assertEquals(0, eight.get(0).localX());
        assertEquals(0, eight.get(0).localY());
        assertEquals(0, eight.get(0).localZ());
        assertEquals(8, eight.get(1).localX());
        assertEquals(8, eight.get(1).localY());
        assertEquals(8, eight.get(1).localZ());
    }

    @Test
    void representativeUsesDominantMaterialThenHighestSample() {
        var cell = TerrainMapColorAggregator.aggregate(List.of(
                new TerrainMapColorAggregator.Sample<>(0, 0, 0, "stone", 0x707070),
                new TerrainMapColorAggregator.Sample<>(1, 1, 1, "grass", 0x70A040),
                new TerrainMapColorAggregator.Sample<>(2, 3, 2, "grass", 0x70A040)), 4).getFirst();

        assertEquals("grass", cell.representative().material());
        assertEquals(3, cell.representative().localY());
    }

    @Test
    void rejectsNonDividingCellSize() {
        assertThrows(IllegalArgumentException.class,
                () -> TerrainMapColorAggregator.aggregate(List.of(), 3));
    }

    @Test
    void aggregatesToFourAndEightVoxelCellsWithRepresentativeMaterial() {
        var samples = List.of(
                new TerrainMaterialAggregator.Sample<>(0, 0, 0, "stone"),
                new TerrainMaterialAggregator.Sample<>(1, 1, 1, "stone"),
                new TerrainMaterialAggregator.Sample<>(2, 3, 2, "grass"),
                new TerrainMaterialAggregator.Sample<>(8, 9, 8, "water"));

        var mid = TerrainMaterialAggregator.aggregate(samples, 4);
        assertEquals(2, mid.size());
        assertEquals("stone", mid.getFirst().representative().material());
        assertEquals(4, mid.getFirst().size());

        var far = TerrainMaterialAggregator.aggregate(samples, 8);
        assertEquals(2, far.size());
        assertEquals(8, far.getFirst().size());
    }

    @Test
    void tiesPreferHighestVisibleSampleThenStableInputOrder() {
        var highWins = TerrainMaterialAggregator.aggregate(List.of(
                new TerrainMaterialAggregator.Sample<>(0, 0, 0, "stone"),
                new TerrainMaterialAggregator.Sample<>(1, 3, 1, "grass")), 4);
        assertEquals("grass", highWins.getFirst().representative().material());

        var firstWins = TerrainMaterialAggregator.aggregate(List.of(
                new TerrainMaterialAggregator.Sample<>(0, 3, 0, "stone"),
                new TerrainMaterialAggregator.Sample<>(1, 3, 1, "grass")), 4);
        assertEquals("stone", firstWins.getFirst().representative().material());
    }

    @Test
    void rejectsInvalidCellSizesAndCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> TerrainMaterialAggregator.aggregate(List.of(), 3));
        assertThrows(IllegalArgumentException.class,
                () -> new TerrainMaterialAggregator.Sample<>(16, 0, 0, "stone"));
    }

    @Test
    void aggregatesVisibleCellsIntoMidAndFarVoxels() {
        var model = TerrainCellSample.RenderCategory.MODEL;
        var visible = List.of(
                new TerrainOverviewAggregator.Cell(0, 0, 0, model),
                new TerrainOverviewAggregator.Cell(3, 3, 3, model),
                new TerrainOverviewAggregator.Cell(4, 0, 0, model),
                new TerrainOverviewAggregator.Cell(15, 15, 15, model));

        assertEquals(3, TerrainOverviewAggregator.aggregate(visible, 4).size());
        assertEquals(2, TerrainOverviewAggregator.aggregate(visible, 8).size());
        assertEquals(new TerrainOverviewAggregator.Cell(0, 0, 0, model),
                TerrainOverviewAggregator.aggregate(visible, 8).getFirst());
        assertThrows(IllegalArgumentException.class, () -> TerrainOverviewAggregator.aggregate(visible, 3));
    }
}
