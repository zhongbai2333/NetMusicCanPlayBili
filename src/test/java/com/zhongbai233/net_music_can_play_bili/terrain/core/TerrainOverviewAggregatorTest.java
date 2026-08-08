package com.zhongbai233.net_music_can_play_bili.terrain.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerrainOverviewAggregatorTest {
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