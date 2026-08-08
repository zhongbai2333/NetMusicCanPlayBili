package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 首版安全 mesher：只消费不可变 section snapshot，生成外露方块面，不调用任何 Minecraft 或模组代码。
 */
public final class TerrainSurfaceMesher {
    private static final TerrainSurfaceFace.Direction[] DIRECTIONS = TerrainSurfaceFace.Direction.values();
    private final int maxFaces;

    public TerrainSurfaceMesher(int maxFaces) {
        if (maxFaces <= 0) {
            throw new IllegalArgumentException("maxFaces must be positive");
        }
        this.maxFaces = maxFaces;
    }

    public TerrainSurfaceMesh mesh(TerrainSectionSnapshot snapshot, TerrainLodLevel lod) {
        java.util.Objects.requireNonNull(snapshot, "snapshot");
        java.util.Objects.requireNonNull(lod, "lod");
        if (lod == TerrainLodLevel.UNKNOWN) {
            return new TerrainSurfaceMesh(snapshot.key(), lod, List.of(), false, 0L);
        }
        List<TerrainSurfaceFace> faces = new ArrayList<>();
        boolean truncated = false;
        for (int y = 0; y < TerrainSectionKey.SIZE && !truncated; y++) {
            for (int z = 0; z < TerrainSectionKey.SIZE && !truncated; z++) {
                for (int x = 0; x < TerrainSectionKey.SIZE && !truncated; x++) {
                    TerrainCellSample cell = snapshot.cell(x, y, z);
                    if (!solid(cell)) {
                        continue;
                    }
                    for (TerrainSurfaceFace.Direction direction : DIRECTIONS) {
                        if (faces.size() >= maxFaces) {
                            truncated = true;
                            break;
                        }
                        if (!solid(neighbor(snapshot, x, y, z, direction))) {
                            faces.add(new TerrainSurfaceFace(x, y, z, direction, cell.renderCategory()));
                        }
                    }
                }
            }
        }
        return new TerrainSurfaceMesh(snapshot.key(), lod, faces, truncated, faces.size() * 32L);
    }

    private static TerrainCellSample neighbor(TerrainSectionSnapshot snapshot, int x, int y, int z,
            TerrainSurfaceFace.Direction direction) {
        int nx = x + direction.dx();
        int ny = y + direction.dy();
        int nz = z + direction.dz();
        if (nx < 0 || nx >= TerrainSectionKey.SIZE || ny < 0 || ny >= TerrainSectionKey.SIZE
                || nz < 0 || nz >= TerrainSectionKey.SIZE) {
            // section 外的邻居由相邻 section mesher 负责；首版宁可绘制边界面，不隐藏可能可见的地形。
            return TerrainCellSample.unknown();
        }
        return snapshot.cell(nx, ny, nz);
    }

    private static boolean solid(TerrainCellSample cell) {
        return cell.availability() == TerrainCellSample.Availability.LOADED
                && cell.renderCategory() != TerrainCellSample.RenderCategory.AIR
                && cell.renderCategory() != TerrainCellSample.RenderCategory.INVISIBLE;
    }
}