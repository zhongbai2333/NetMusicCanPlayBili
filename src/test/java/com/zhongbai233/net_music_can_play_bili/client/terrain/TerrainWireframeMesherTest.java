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
    void adjacentMacroCellsCullTheirInternalFaceAndKeepTheSurfaceStep() {
        var model = TerrainCellSample.RenderCategory.MODEL;
        var cells = List.of(new TerrainOverviewCell(0, 0, 0, 4, model),
                new TerrainOverviewCell(4, 0, 0, 4, model));
        var segments = TerrainWireframeMesher.mesh(cells, new TerrainBounds(0, 0, 0, 7, 3, 3));

        assertEquals(16, segments.size());
        assertTrue(segments.stream().anyMatch(segment -> segment.x1() == 0 && segment.x2() == 8));
    }

    @Test
    void filledSectionKeepsFourVoxelGridOnlyOnAirContactSurfaces() {
        var model = TerrainCellSample.RenderCategory.MODEL;
        var cells = new ArrayList<TerrainOverviewCell>();
        for (int y = 0; y < 16; y += 4) {
            for (int z = 0; z < 16; z += 4) {
                for (int x = 0; x < 16; x += 4) {
                    cells.add(new TerrainOverviewCell(x, y, z, 4, model));
                }
            }
        }

        var segments = TerrainWireframeMesher.mesh(cells,
                new TerrainBounds(0, 0, 0, 15, 15, 15));

        assertEquals(48, segments.size());
        assertTrue(segments.stream().allMatch(segment ->
                segment.x1() == 0 && segment.x2() == 0
                        || segment.x1() == 16 && segment.x2() == 16
                        || segment.y1() == 0 && segment.y2() == 0
                        || segment.y1() == 16 && segment.y2() == 16
                        || segment.z1() == 0 && segment.z2() == 0
                        || segment.z1() == 16 && segment.z2() == 16));
    }

    @Test
    void identicalColoredEdgesMergeTwoToOneAndAverageTheirColor() {
        var model = TerrainCellSample.RenderCategory.MODEL;
        var cells = List.of(
                new TerrainOverviewCell(0, 0, 0, 4, model, 0xFFFF0000),
                new TerrainOverviewCell(0, 0, 0, 4, model, 0xFF0000FF));

        var segments = TerrainWireframeMesher.mesh(cells,
                new TerrainBounds(0, 0, 0, 3, 3, 3));

        assertEquals(12, segments.size());
        assertTrue(segments.stream().allMatch(segment -> segment.color() == 0xFF800080));
    }

    @Test
    void mixedCellSizesMergePartialOverlapsAndRespectBounds() {
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
    void mixedEightAndFourVoxelNeighborsCullTheirCoveredInternalFace() {
        var model = TerrainCellSample.RenderCategory.MODEL;
        var cells = new ArrayList<TerrainOverviewCell>();
        cells.add(new TerrainOverviewCell(0, 0, 0, 8, model));
        for (int y = 0; y < 8; y += 4) {
            for (int z = 0; z < 8; z += 4) {
                cells.add(new TerrainOverviewCell(8, y, z, 4, model));
            }
        }

        var segments = TerrainWireframeMesher.mesh(cells,
                new TerrainBounds(0, 0, 0, 15, 7, 7));

        assertTrue(segments.stream().noneMatch(segment -> segment.x1() == 8 && segment.x2() == 8
                && segment.z1() == 4 && segment.z2() == 4),
                "internal Y line remained on the covered mixed-size face");
        assertTrue(segments.stream().noneMatch(segment -> segment.x1() == 8 && segment.x2() == 8
                && segment.y1() == 4 && segment.y2() == 4),
                "internal Z line remained on the covered mixed-size face");
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

    @Test
    void filledSectionKeepsEightVoxelGridAndStableSixteenVoxelOutline() {
        var model = TerrainCellSample.RenderCategory.MODEL;
        var cells = new ArrayList<TerrainOverviewCell>();
        for (int y = 0; y < 16; y += 8) {
            for (int z = 0; z < 16; z += 8) {
                for (int x = 0; x < 16; x += 8) {
                    cells.add(new TerrainOverviewCell(x, y, z, 8, model));
                }
            }
        }

        var segments = TerrainWireframeMesher.mesh(cells,
                new TerrainBounds(0, 0, 0, 15, 15, 15));

        assertEquals(24, segments.size());
    }

    @Test
    void adjacentSectionsProduceStableSixteenVoxelStepLines() {
        var model = TerrainCellSample.RenderCategory.MODEL;
        var cells = List.of(new TerrainOverviewCell(0, 0, 0, 16, model),
                new TerrainOverviewCell(16, 0, 0, 16, model));

        var segments = TerrainWireframeMesher.mesh(cells,
                new TerrainBounds(0, 0, 0, 31, 15, 15));

        assertEquals(16, segments.size());
        assertTrue(segments.stream().anyMatch(segment -> segment.x1() == 16 && segment.x2() == 16));
    }
}
