package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 不分配完整 coverage 的有界游标。水平面从相机所在 section 按方环向外扫描，
 * 每次调用最多检查 {@code candidateBudget} 个 section 候选。
 */
public final class TerrainCoverageCursor {
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final int centerX;
    private final int centerZ;
    private final int maxRing;
    private int ring;
    private int columnIndex;
    private int y;
    private boolean exhausted;

    public TerrainCoverageCursor(TerrainBounds bounds, TerrainSectionKey focus) {
        java.util.Objects.requireNonNull(bounds, "bounds");
        java.util.Objects.requireNonNull(focus, "focus");
        minX = Math.floorDiv(bounds.minX(), TerrainSectionKey.SIZE);
        minY = Math.floorDiv(bounds.minY(), TerrainSectionKey.SIZE);
        minZ = Math.floorDiv(bounds.minZ(), TerrainSectionKey.SIZE);
        maxX = Math.floorDiv(bounds.maxX(), TerrainSectionKey.SIZE);
        maxY = Math.floorDiv(bounds.maxY(), TerrainSectionKey.SIZE);
        maxZ = Math.floorDiv(bounds.maxZ(), TerrainSectionKey.SIZE);
        centerX = Math.clamp(focus.x(), minX, maxX);
        centerZ = Math.clamp(focus.z(), minZ, maxZ);
        maxRing = Math.max(Math.max(centerX - minX, maxX - centerX),
                Math.max(centerZ - minZ, maxZ - centerZ));
        y = minY;
    }

    public List<TerrainSectionKey> next(int candidateBudget) {
        if (candidateBudget <= 0 || exhausted) {
            return List.of();
        }
        List<TerrainSectionKey> result = new ArrayList<>(candidateBudget);
        int checked = 0;
        while (checked < candidateBudget && !exhausted) {
            int[] column = column(ring, columnIndex);
            TerrainSectionKey key = new TerrainSectionKey(centerX + column[0], y, centerZ + column[1]);
            checked++;
            advance();
            if (key.x() >= minX && key.x() <= maxX && key.z() >= minZ && key.z() <= maxZ) {
                result.add(key);
            }
        }
        return List.copyOf(result);
    }

    public boolean exhausted() {
        return exhausted;
    }

    private void advance() {
        if (y < maxY) {
            y++;
            return;
        }
        y = minY;
        columnIndex++;
        int columns = ring == 0 ? 1 : 8 * ring;
        if (columnIndex < columns) {
            return;
        }
        columnIndex = 0;
        ring++;
        if (ring > maxRing) {
            exhausted = true;
        }
    }

    private static int[] column(int ring, int index) {
        if (ring == 0) {
            return new int[] { 0, 0 };
        }
        int top = 2 * ring + 1;
        if (index < top) {
            return new int[] { -ring + index, -ring };
        }
        index -= top;
        int side = 2 * ring;
        if (index < side) {
            return new int[] { ring, -ring + 1 + index };
        }
        index -= side;
        if (index < side) {
            return new int[] { ring - 1 - index, ring };
        }
        index -= side;
        return new int[] { -ring, ring - 1 - index };
    }
}