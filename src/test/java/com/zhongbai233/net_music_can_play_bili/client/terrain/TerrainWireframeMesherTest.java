package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainWireframeMesherTest {
    @Test
    void adjacentBoxesCancelSharedEdgesAndMergeCollinearSegments() {
        var model = TerrainCellSample.RenderCategory.MODEL;
        var cells = List.of(new TerrainOverviewCell(0, 0, 0, 4, model),
                new TerrainOverviewCell(4, 0, 0, 4, model));
        var segments = TerrainWireframeMesher.mesh(cells, new TerrainBounds(0, 0, 0, 7, 3, 3));

        assertTrue(segments.size() < 24);
        assertTrue(segments.stream().anyMatch(segment -> segment.x1() == 0 && segment.x2() == 8));
    }

    @Test
    void mixedCellSizesCancelPartialOverlapsAndRespectBounds() {
        var model = TerrainCellSample.RenderCategory.MODEL;
        var cells = List.of(new TerrainOverviewCell(0, 0, 0, 8, model),
                new TerrainOverviewCell(8, 0, 0, 4, model));
        var bounds = new TerrainBounds(2, 0, 0, 11, 7, 7);
        var segments = TerrainWireframeMesher.mesh(cells, bounds);

        assertTrue(segments.stream().allMatch(segment -> segment.x1() >= 2 && segment.x2() >= 2
                && segment.x1() <= 12 && segment.x2() <= 12));
        assertEquals(segments, TerrainWireframeMesher.mesh(cells, bounds));
    }

    @Test
    void regularGridReducesBoxEdgeSubmissionsByAtLeastSeventyFivePercent() {
        var model = TerrainCellSample.RenderCategory.MODEL;
        var cells = new ArrayList<TerrainOverviewCell>();
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    cells.add(new TerrainOverviewCell(x * 4, y * 4, z * 4, 4, model));
                }
            }
        }
        int originalBoxEdges = cells.size() * 12;
        int mergedSegments = TerrainWireframeMesher.mesh(cells,
                new TerrainBounds(0, 0, 0, 15, 15, 15)).size();

        assertTrue(mergedSegments < originalBoxEdges / 4,
                () -> "expected <75% of box edges, got " + mergedSegments + "/" + originalBoxEdges);
    }
}