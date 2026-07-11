package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure calculations for applying dirty chunk notifications to cached Pad map
 * data.
 */
final class PadMapDirtyInvalidation {
    private static final int CHUNK_SIZE = 16;

    private PadMapDirtyInvalidation() {
    }

    static boolean touchesSnapshot(PadMapSnapshot snapshot, int cellSize,
            List<PadMapDirtyChunkTracker.Key> dirtyChunks) {
        if (snapshot == null || dirtyChunks == null || dirtyChunks.isEmpty()) {
            return false;
        }
        Bounds bounds = snapshotWorldBounds(snapshot, cellSize);
        for (PadMapDirtyChunkTracker.Key dirtyChunk : dirtyChunks) {
            Bounds chunk = chunkWorldBounds(dirtyChunk);
            if (chunk.maxX() >= bounds.minX() && chunk.minX() <= bounds.maxX()
                    && chunk.maxZ() >= bounds.minZ() && chunk.minZ() <= bounds.maxZ()) {
                return true;
            }
        }
        return false;
    }

    static List<CellRange> cellRanges(List<PadMapDirtyChunkTracker.Key> dirtyChunks, int cellSize) {
        if (dirtyChunks == null || dirtyChunks.isEmpty() || cellSize <= 0) {
            return List.of();
        }
        List<CellRange> ranges = new ArrayList<>(dirtyChunks.size());
        for (PadMapDirtyChunkTracker.Key dirtyChunk : dirtyChunks) {
            int minCellX = Math.floorDiv(dirtyChunk.chunkX() * CHUNK_SIZE, cellSize);
            int maxCellX = Math.floorDiv(dirtyChunk.chunkX() * CHUNK_SIZE + CHUNK_SIZE - 1, cellSize);
            int minCellZ = Math.floorDiv(dirtyChunk.chunkZ() * CHUNK_SIZE, cellSize);
            int maxCellZ = Math.floorDiv(dirtyChunk.chunkZ() * CHUNK_SIZE + CHUNK_SIZE - 1, cellSize);
            ranges.add(new CellRange(minCellX, maxCellX, minCellZ, maxCellZ));
        }
        return ranges;
    }

    /**
     * 保留快照中未受影响的缓存，只将 dirty chunk 覆盖的 cell 还原为 UNKNOWN。
     * 返回 null 表示 dirty 范围与快照没有交集。
     */
    static PadMapSnapshot invalidateSnapshot(PadMapSnapshot snapshot, int cellSize,
            List<PadMapDirtyChunkTracker.Key> dirtyChunks) {
        if (snapshot == null || snapshot.tiles() == null || cellSize <= 0) {
            return null;
        }
        List<CellRange> ranges = cellRanges(dirtyChunks, cellSize);
        if (ranges.isEmpty()) {
            return null;
        }
        PadMapTileKind[] tiles = java.util.Arrays.copyOf(snapshot.tiles(), snapshot.tiles().length);
        boolean changed = false;
        int originCellX = Math.floorDiv(snapshot.centerX(), cellSize) - snapshot.width() / 2;
        int originCellZ = Math.floorDiv(snapshot.centerZ(), cellSize) - snapshot.height() / 2;
        for (CellRange range : ranges) {
            int minX = Math.max(0, range.minCellX() - originCellX);
            int maxX = Math.min(snapshot.width() - 1, range.maxCellX() - originCellX);
            int minZ = Math.max(0, range.minCellZ() - originCellZ);
            int maxZ = Math.min(snapshot.height() - 1, range.maxCellZ() - originCellZ);
            for (int z = minZ; z <= maxZ; z++) {
                int row = z * snapshot.width();
                for (int x = minX; x <= maxX; x++) {
                    int index = row + x;
                    if (index < tiles.length && tiles[index] != PadMapTileKind.UNKNOWN) {
                        tiles[index] = PadMapTileKind.UNKNOWN;
                        changed = true;
                    }
                }
            }
        }
        if (!changed) {
            return null;
        }
        return new PadMapSnapshot(snapshot.centerX(), snapshot.centerY(), snapshot.centerZ(),
                snapshot.cellSizeBlocks(), snapshot.width(), snapshot.height(), tiles, snapshot.displayScale());
    }

    static int affectedCellCount(PadMapSnapshot snapshot, int cellSize,
            List<PadMapDirtyChunkTracker.Key> dirtyChunks) {
        if (snapshot == null || cellSize <= 0) {
            return 0;
        }
        int originCellX = Math.floorDiv(snapshot.centerX(), cellSize) - snapshot.width() / 2;
        int originCellZ = Math.floorDiv(snapshot.centerZ(), cellSize) - snapshot.height() / 2;
        int count = 0;
        for (CellRange range : cellRanges(dirtyChunks, cellSize)) {
            int width = Math.max(0, Math.min(snapshot.width() - 1, range.maxCellX() - originCellX)
                    - Math.max(0, range.minCellX() - originCellX) + 1);
            int height = Math.max(0, Math.min(snapshot.height() - 1, range.maxCellZ() - originCellZ)
                    - Math.max(0, range.minCellZ() - originCellZ) + 1);
            count += width * height;
        }
        return count;
    }

    private static Bounds snapshotWorldBounds(PadMapSnapshot snapshot, int cellSize) {
        int halfW = snapshot.width() / 2;
        int halfH = snapshot.height() / 2;
        int minWorldX = snapshot.centerX() - halfW * cellSize;
        int maxWorldX = snapshot.centerX() + (snapshot.width() - halfW - 1) * cellSize;
        int minWorldZ = snapshot.centerZ() - halfH * cellSize;
        int maxWorldZ = snapshot.centerZ() + (snapshot.height() - halfH - 1) * cellSize;
        return new Bounds(minWorldX, maxWorldX, minWorldZ, maxWorldZ);
    }

    private static Bounds chunkWorldBounds(PadMapDirtyChunkTracker.Key dirtyChunk) {
        int minX = dirtyChunk.chunkX() * CHUNK_SIZE;
        int minZ = dirtyChunk.chunkZ() * CHUNK_SIZE;
        return new Bounds(minX, minX + CHUNK_SIZE - 1, minZ, minZ + CHUNK_SIZE - 1);
    }

    record CellRange(int minCellX, int maxCellX, int minCellZ, int maxCellZ) {
        boolean contains(int cellX, int cellZ) {
            return cellX >= minCellX && cellX <= maxCellX && cellZ >= minCellZ && cellZ <= maxCellZ;
        }
    }

    private record Bounds(int minX, int maxX, int minZ, int maxZ) {
    }
}
