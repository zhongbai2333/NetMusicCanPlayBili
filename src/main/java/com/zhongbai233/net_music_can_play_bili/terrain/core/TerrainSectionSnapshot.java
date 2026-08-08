package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.List;

/** 完整 section 的不可变主线程快照，可安全交给后台简化 mesher。 */
public record TerrainSectionSnapshot(TerrainSectionKey key, long generation, List<TerrainCellSample> cells,
        long estimatedBytes) {
    public TerrainSectionSnapshot {
        java.util.Objects.requireNonNull(key, "key");
        cells = List.copyOf(java.util.Objects.requireNonNull(cells, "cells"));
        if (cells.size() != TerrainSectionKey.CELL_COUNT) {
            throw new IllegalArgumentException("terrain section snapshot must contain exactly 4096 cells");
        }
        if (generation < 0L || estimatedBytes < 0L) {
            throw new IllegalArgumentException("snapshot generation and estimatedBytes must be non-negative");
        }
    }

    public TerrainCellSample cell(int localX, int localY, int localZ) {
        if (localX < 0 || localX >= TerrainSectionKey.SIZE || localY < 0 || localY >= TerrainSectionKey.SIZE
                || localZ < 0 || localZ >= TerrainSectionKey.SIZE) {
            throw new IndexOutOfBoundsException("terrain local coordinates must be within [0, 15]");
        }
        return cells.get(index(localX, localY, localZ));
    }

    static int index(int localX, int localY, int localZ) {
        return (localY * TerrainSectionKey.SIZE + localZ) * TerrainSectionKey.SIZE + localX;
    }
}