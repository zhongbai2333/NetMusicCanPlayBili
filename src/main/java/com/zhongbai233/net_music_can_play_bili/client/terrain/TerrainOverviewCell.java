package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample;

/** 全局低 LOD 的一个表面高度采样格，不持有任何 Minecraft 对象。 */
public record TerrainOverviewCell(int worldX, int worldY, int worldZ, int size,
        TerrainCellSample.RenderCategory material) {
    public TerrainOverviewCell {
        if (size <= 0) {
            throw new IllegalArgumentException("overview cell size must be positive");
        }
        java.util.Objects.requireNonNull(material, "material");
    }
}