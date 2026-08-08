package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.List;

/** 后台简化 mesher 的不可变输出。 */
public record TerrainSurfaceMesh(TerrainSectionKey section, TerrainLodLevel lod,
        List<TerrainSurfaceFace> faces, boolean truncated, long estimatedBytes) {
    public TerrainSurfaceMesh {
        java.util.Objects.requireNonNull(section, "section");
        java.util.Objects.requireNonNull(lod, "lod");
        faces = List.copyOf(java.util.Objects.requireNonNull(faces, "faces"));
        if (estimatedBytes < 0L) {
            throw new IllegalArgumentException("estimatedBytes must be non-negative");
        }
    }
}