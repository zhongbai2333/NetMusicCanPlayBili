package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.ArrayList;
import java.util.List;

/** Builds the ordered list of map cells a sampling job should fill. */
final class PadMapSamplePlan {
    private static final int TILE_SIZE = 16;

    private PadMapSamplePlan() {
    }

    static List<Cell> collectPendingCells(int centerX, int centerZ, int cellSize, int width, int height,
            int visibleWidth, int visibleHeight, PadMapTileKind[] tiles) {
        int halfW = width / 2;
        int halfH = height / 2;
        int visibleHalfW = visibleWidth / 2;
        int visibleHalfH = visibleHeight / 2;
        int edgeBand = edgeBand(visibleWidth, visibleHeight);
        int prefetchBand = edgeBand;
        List<Cell> pendingCells = new ArrayList<>();
        for (int z = 0; z < height; z++) {
            int localZ = z - halfH;
            boolean visibleZ = localZ >= -visibleHalfH && localZ < visibleHeight - visibleHalfH;
            int worldZ = centerZ + localZ * cellSize;
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                if (tiles[index] != PadMapTileKind.UNKNOWN) {
                    continue;
                }
                int localX = x - halfW;
                boolean visible = visibleZ && localX >= -visibleHalfW && localX < visibleWidth - visibleHalfW;
                int worldX = centerX + localX * cellSize;
                int tileX = Math.floorDiv(localX, TILE_SIZE);
                int tileZ = Math.floorDiv(localZ, TILE_SIZE);
                int tileDistance = Math.abs(tileX) + Math.abs(tileZ);
                int intraTileIndex = Math.floorMod(localZ, TILE_SIZE) * TILE_SIZE + Math.floorMod(localX, TILE_SIZE);
                int priority = priority(localX, localZ, visibleWidth, visibleHeight, visibleHalfW, visibleHalfH,
                        visible, edgeBand, prefetchBand);
                pendingCells.add(new Cell(index, worldX, worldZ, visible, priority, tileDistance, tileX, tileZ,
                        intraTileIndex));
            }
        }
        return orderByPriorityAndTile(pendingCells, width, height);
    }

    /**
     * Key ranges are small and bounded by snapshot dimensions. Bucket by priority
     * and tile distance first, leaving only tiny per-bucket sorts.
     */
    private static List<Cell> orderByPriorityAndTile(List<Cell> cells, int width, int height) {
        int maxTileDistance = Math.floorDiv(width / 2, TILE_SIZE) + Math.floorDiv(height / 2, TILE_SIZE) + 2;
        @SuppressWarnings("unchecked")
        List<Cell>[][] buckets = new List[4][maxTileDistance + 1];
        for (Cell cell : cells) {
            int distance = Math.min(maxTileDistance, cell.tileDistance());
            List<Cell> bucket = buckets[cell.priority()][distance];
            if (bucket == null) {
                bucket = new ArrayList<>();
                buckets[cell.priority()][distance] = bucket;
            }
            bucket.add(cell);
        }
        List<Cell> ordered = new ArrayList<>(cells.size());
        for (List<Cell>[] priorityBuckets : buckets) {
            for (List<Cell> bucket : priorityBuckets) {
                if (bucket == null) {
                    continue;
                }
                bucket.sort((a, b) -> {
                    int byZ = Integer.compare(a.tileZ(), b.tileZ());
                    if (byZ != 0) return byZ;
                    int byX = Integer.compare(a.tileX(), b.tileX());
                    return byX != 0 ? byX : Integer.compare(a.intraTileIndex(), b.intraTileIndex());
                });
                ordered.addAll(bucket);
            }
        }
        return ordered;
    }

    private static int priority(int localX, int localZ, int visibleWidth, int visibleHeight, int visibleHalfW,
            int visibleHalfH, boolean visible, int edgeBand, int prefetchBand) {
        int minVisibleX = -visibleHalfW;
        int maxVisibleX = visibleWidth - visibleHalfW - 1;
        int minVisibleZ = -visibleHalfH;
        int maxVisibleZ = visibleHeight - visibleHalfH - 1;
        int centerBandX = Math.max(16, visibleWidth / 6);
        int centerBandZ = Math.max(16, visibleHeight / 6);
        if (Math.abs(localX) <= centerBandX && Math.abs(localZ) <= centerBandZ) {
            return 0;
        }
        if (visible) {
            return 1;
        }
        if (distanceOutsideRect(localX, localZ, minVisibleX, maxVisibleX, minVisibleZ, maxVisibleZ) <= prefetchBand) {
            return 2;
        }
        return 3;
    }

    private static int edgeBand(int visibleWidth, int visibleHeight) {
        int shortSide = Math.max(1, Math.min(visibleWidth, visibleHeight));
        return Math.max(16, Math.min(64, shortSide / 4));
    }

    private static int distanceOutsideRect(int x, int z, int minX, int maxX, int minZ, int maxZ) {
        int dx = x < minX ? minX - x : x > maxX ? x - maxX : 0;
        int dz = z < minZ ? minZ - z : z > maxZ ? z - maxZ : 0;
        return Math.max(dx, dz);
    }

    record Cell(int index, int worldX, int worldZ, boolean visible, int priority, int tileDistance, int tileX,
            int tileZ,
            int intraTileIndex) {
    }
}
