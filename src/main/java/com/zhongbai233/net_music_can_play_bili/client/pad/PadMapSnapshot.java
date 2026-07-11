package com.zhongbai233.net_music_can_play_bili.client.pad;

public record PadMapSnapshot(int centerX, int centerY, int centerZ, int cellSizeBlocks, int width, int height,
        PadMapTileKind[] tiles, float displayScale, long contentSignature, long layoutSignature) {
    public PadMapSnapshot {
        if (contentSignature == 0L) {
            contentSignature = computeContentSignature(centerX, centerY, centerZ, cellSizeBlocks, width, height, tiles,
                    displayScale);
        }
        if (layoutSignature == 0L) {
            layoutSignature = computeLayoutSignature(centerX, centerY, centerZ, cellSizeBlocks, width, height, tiles,
                    displayScale);
        }
    }

    public PadMapSnapshot(int centerX, int centerY, int centerZ, int cellSizeBlocks, int width, int height,
            PadMapTileKind[] tiles, float displayScale) {
        this(centerX, centerY, centerZ, cellSizeBlocks, width, height, tiles, displayScale, 0L, 0L);
    }

    public PadMapSnapshot(int centerX, int centerZ, int cellSizeBlocks, int width, int height,
            PadMapTileKind[] tiles, float displayScale) {
        this(centerX, 0, centerZ, cellSizeBlocks, width, height, tiles, displayScale);
    }

    public PadMapSnapshot(int centerX, int centerZ, int cellSizeBlocks, int width, int height,
            PadMapTileKind[] tiles) {
        this(centerX, 0, centerZ, cellSizeBlocks, width, height, tiles, 1.0F);
    }

    public PadMapSnapshot(int centerX, int centerY, int centerZ, int cellSizeBlocks, int width, int height,
            PadMapTileKind[] tiles) {
        this(centerX, centerY, centerZ, cellSizeBlocks, width, height, tiles, 1.0F);
    }

    public PadMapSnapshot(int centerX, int centerZ, int cellSizeBlocks, int size, PadMapTileKind[] tiles) {
        this(centerX, 0, centerZ, cellSizeBlocks, size, size, tiles, 1.0F);
    }

    public int size() {
        return width;
    }

    public PadMapTileKind tile(int x, int z) {
        if (x < 0 || z < 0 || x >= width || z >= height || tiles == null) {
            return PadMapTileKind.UNKNOWN;
        }
        int index = z * width + x;
        return index >= 0 && index < tiles.length ? tiles[index] : PadMapTileKind.UNKNOWN;
    }

    public boolean hasUnknownTiles() {
        if (tiles == null || tiles.length < width * height) {
            return true;
        }
        for (int i = 0; i < width * height; i++) {
            if (tiles[i] == null || tiles[i] == PadMapTileKind.UNKNOWN) {
                return true;
            }
        }
        return false;
    }

    private static long computeContentSignature(int centerX, int centerY, int centerZ, int cellSizeBlocks, int width,
            int height, PadMapTileKind[] tiles, float displayScale) {
        long signature = baseLayoutSignature(centerX, centerY, centerZ, cellSizeBlocks, width, height, displayScale);
        if (tiles != null) {
            for (PadMapTileKind tile : tiles) {
                signature = mixSignature(signature, tile == null ? PadMapTileKind.UNKNOWN.ordinal() : tile.ordinal());
            }
        }
        return signature;
    }

    private static long computeLayoutSignature(int centerX, int centerY, int centerZ, int cellSizeBlocks, int width,
            int height, PadMapTileKind[] tiles, float displayScale) {
        long signature = baseLayoutSignature(centerX, centerY, centerZ, cellSizeBlocks, width, height, displayScale);
        signature = mixSignature(signature, containsIndoorFloor(tiles) ? 1 : 0);
        return signature;
    }

    private static long baseLayoutSignature(int centerX, int centerY, int centerZ, int cellSizeBlocks, int width,
            int height, float displayScale) {
        long signature = 1469598103934665603L;
        signature = mixSignature(signature, centerX);
        signature = mixSignature(signature, centerY);
        signature = mixSignature(signature, centerZ);
        signature = mixSignature(signature, cellSizeBlocks);
        signature = mixSignature(signature, width);
        signature = mixSignature(signature, height);
        signature = mixSignature(signature, Float.floatToIntBits(displayScale));
        return signature;
    }

    private static boolean containsIndoorFloor(PadMapTileKind[] tiles) {
        if (tiles == null) {
            return false;
        }
        for (PadMapTileKind tile : tiles) {
            if (tile == PadMapTileKind.INDOOR_FLOOR) {
                return true;
            }
        }
        return false;
    }

    private static long mixSignature(long signature, int value) {
        return (signature ^ value) * 1099511628211L;
    }
}