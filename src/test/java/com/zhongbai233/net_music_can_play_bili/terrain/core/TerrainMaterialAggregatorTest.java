package com.zhongbai233.net_music_can_play_bili.terrain.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerrainMaterialAggregatorTest {
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
}
