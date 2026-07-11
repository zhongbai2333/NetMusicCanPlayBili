package com.zhongbai233.net_music_can_play_bili.client.pad;

/**
 * Reuses tiles from a previous Pad map snapshot when a new sampling job
 * overlaps it.
 */
final class PadMapSnapshotSeeder {
    private PadMapSnapshotSeeder() {
    }

    static void seed(PadMapSnapshot previous, PadMapTileKind[] targetTiles, int centerX, int centerY, int centerZ,
            int cellSize, int width, int height) {
        if (previous == null || targetTiles == null || previous.width() != width || previous.height() != height
                || previous.cellSizeBlocks() != cellSize || previous.centerY() != centerY
                || previous.tiles() == null) {
            return;
        }
        int halfW = width / 2;
        int halfH = height / 2;
        int previousHalfW = previous.width() / 2;
        int previousHalfH = previous.height() / 2;
        for (int z = 0; z < height; z++) {
            int worldZ = centerZ + (z - halfH) * cellSize;
            int previousZ = Math.floorDiv(worldZ - previous.centerZ(), cellSize) + previousHalfH;
            if (previousZ < 0 || previousZ >= previous.height()) {
                continue;
            }
            for (int x = 0; x < width; x++) {
                int worldX = centerX + (x - halfW) * cellSize;
                int previousX = Math.floorDiv(worldX - previous.centerX(), cellSize) + previousHalfW;
                if (previousX < 0 || previousX >= previous.width()) {
                    continue;
                }
                PadMapTileKind kind = previous.tile(previousX, previousZ);
                if (kind != PadMapTileKind.UNKNOWN) {
                    targetTiles[z * width + x] = kind;
                }
            }
        }
    }
}
