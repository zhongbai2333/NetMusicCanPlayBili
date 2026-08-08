package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSurfaceMesh;

import java.util.List;
import java.util.Set;

/** PIP 只读的不可变地形帧；不包含 Level、BlockState 或后台任务。 */
public record TerrainPreviewFrame(long generation, int originX, int originY, int originZ,
    double coreCenterX, double coreCenterY, double coreCenterZ,
        TerrainBounds bounds, List<TerrainOverviewCell> overviewCells,
    List<TerrainWireframeMesher.Segment> wireframeSegments,
        List<TerrainSurfaceMesh> highDetailMeshes, List<TerrainBlockSectionSnapshot> fullDetailSections,
        Set<TerrainSectionKey> fullDetailSectionKeys,
        Set<TerrainSectionKey> removedSections,
        int pendingSections,
        int sampledSections) {
    private static final TerrainPreviewFrame EMPTY = new TerrainPreviewFrame(0L, 0, 0, 0, 0.0D, 0.0D, 0.0D,
            new TerrainBounds(0, 0, 0, 0, 0, 0), List.of(), List.of(), List.of(), List.of(), Set.of(), Set.of(), 0, 0);

    public TerrainPreviewFrame {
        java.util.Objects.requireNonNull(bounds, "bounds");
        if (!Double.isFinite(coreCenterX) || !Double.isFinite(coreCenterY) || !Double.isFinite(coreCenterZ)) {
            throw new IllegalArgumentException("terrain core center must be finite");
        }
        overviewCells = List.copyOf(java.util.Objects.requireNonNull(overviewCells, "overviewCells"));
        wireframeSegments = List.copyOf(java.util.Objects.requireNonNull(wireframeSegments, "wireframeSegments"));
        highDetailMeshes = List.copyOf(java.util.Objects.requireNonNull(highDetailMeshes, "highDetailMeshes"));
        fullDetailSections = List.copyOf(java.util.Objects.requireNonNull(fullDetailSections, "fullDetailSections"));
        fullDetailSectionKeys = Set.copyOf(java.util.Objects.requireNonNull(
            fullDetailSectionKeys, "fullDetailSectionKeys"));
        removedSections = Set.copyOf(java.util.Objects.requireNonNull(removedSections, "removedSections"));
        if (generation < 0L || pendingSections < 0 || sampledSections < 0) {
            throw new IllegalArgumentException("terrain frame counters must be non-negative");
        }
    }

    public static TerrainPreviewFrame empty() {
        return EMPTY;
    }
}