package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure-Java voxel aggregation used by MID/FAR terrain previews. */
public final class TerrainOverviewAggregator {
    private TerrainOverviewAggregator() {
    }

    public static List<Cell> aggregate(List<Cell> visible, int size) {
        java.util.Objects.requireNonNull(visible, "visible");
        if (size <= 0 || TerrainSectionKey.SIZE % size != 0) {
            throw new IllegalArgumentException("overview size must divide section size");
        }
        int dimension = TerrainSectionKey.SIZE / size;
        Map<Integer, Cell> cells = new LinkedHashMap<>();
        for (Cell cell : visible) {
            int gx = cell.localX() / size;
            int gy = cell.localY() / size;
            int gz = cell.localZ() / size;
            int index = (gy * dimension + gz) * dimension + gx;
            cells.putIfAbsent(index, new Cell(gx * size, gy * size, gz * size, cell.material()));
        }
        return List.copyOf(new ArrayList<>(cells.values()));
    }

    public record Cell(int localX, int localY, int localZ, TerrainCellSample.RenderCategory material) {
        public Cell {
            if (localX < 0 || localX >= TerrainSectionKey.SIZE || localY < 0 || localY >= TerrainSectionKey.SIZE
                    || localZ < 0 || localZ >= TerrainSectionKey.SIZE) {
                throw new IllegalArgumentException("terrain overview local coordinates must be within [0, 15]");
            }
            java.util.Objects.requireNonNull(material, "material");
        }
    }
}