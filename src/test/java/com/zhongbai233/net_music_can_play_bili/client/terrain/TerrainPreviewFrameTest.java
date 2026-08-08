package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainLodLevel;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSurfaceMesh;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerrainPreviewFrameTest {
    @Test
    void frameDefensivelyCopiesOverviewAndMeshLists() {
        var overview = new ArrayList<TerrainOverviewCell>();
        overview.add(new TerrainOverviewCell(4, 70, 8, 4, TerrainCellSample.RenderCategory.MODEL));
        var meshes = new ArrayList<TerrainSurfaceMesh>();
        meshes.add(new TerrainSurfaceMesh(new TerrainSectionKey(0, 4, 0), TerrainLodLevel.NEAR,
                List.of(), false, 0L));
        var fullDetail = new ArrayList<TerrainBlockSectionSnapshot>();
        fullDetail.add(new TerrainBlockSectionSnapshot(new TerrainSectionKey(0, 4, 0),
                List.of(), 256L));
        var removed = new HashSet<TerrainSectionKey>();
        removed.add(new TerrainSectionKey(1, 4, 0));

        TerrainPreviewFrame frame = new TerrainPreviewFrame(3L, 0, 64, 0, 0.5D, 64.5D, 0.5D,
                new TerrainBounds(-128, 0, -128, 127, 255, 127), overview, List.of(), meshes, fullDetail,
                Set.of(new TerrainSectionKey(0, 4, 0)), removed, 2, 1);
        overview.clear();
        meshes.clear();
        fullDetail.clear();
        removed.clear();

        assertEquals(1, frame.overviewCells().size());
        assertEquals(1, frame.highDetailMeshes().size());
        assertEquals(1, frame.fullDetailSections().size());
        assertEquals(1, frame.removedSections().size());
        assertThrows(UnsupportedOperationException.class, () -> frame.overviewCells().clear());
        assertThrows(UnsupportedOperationException.class, () -> frame.wireframeSegments().clear());
        assertThrows(UnsupportedOperationException.class, () -> frame.highDetailMeshes().clear());
        assertThrows(UnsupportedOperationException.class, () -> frame.fullDetailSections().clear());
        assertThrows(UnsupportedOperationException.class, () -> frame.fullDetailSectionKeys().clear());
        assertThrows(UnsupportedOperationException.class, () -> frame.removedSections().clear());
    }

    @Test
    void overviewCellsRejectInvalidSizeAndFrameRejectsNegativeCounters() {
        assertThrows(IllegalArgumentException.class, () -> new TerrainOverviewCell(0, 0, 0, 0,
                TerrainCellSample.RenderCategory.MODEL));
        assertThrows(IllegalArgumentException.class, () -> new TerrainPreviewFrame(1L, 0, 0, 0, 0.5D, 0.5D, 0.5D,
                new TerrainBounds(0, 0, 0, 0, 0, 0), List.of(), List.of(), List.of(), List.of(), Set.of(), Set.of(), -1, 0));
    }
}