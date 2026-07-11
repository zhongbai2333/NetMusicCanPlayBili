package com.zhongbai233.net_music_can_play_bili.client.pad;

/** Lightweight self tests for reusing tiles from previous Pad map snapshots. */
public final class PadMapSnapshotSeederSelfTest {
    private PadMapSnapshotSeederSelfTest() {
    }

    public static void main(String[] args) {
        reusesOverlappingKnownTiles();
        doesNotCopyUnknownTiles();
        rejectsMismatchedFloorOrCellSize();
        System.out.println("PadMapSnapshotSeederSelfTest passed");
    }

    private static void reusesOverlappingKnownTiles() {
        PadMapTileKind[] previousTiles = unknownTiles(4, 4);
        previousTiles[1 * 4 + 1] = PadMapTileKind.GRASS;
        previousTiles[1 * 4 + 2] = PadMapTileKind.WATER;
        PadMapSnapshot previous = new PadMapSnapshot(0, 64, 0, 1, 4, 4, previousTiles, 1.0F);
        PadMapTileKind[] target = unknownTiles(4, 4);
        PadMapSnapshotSeeder.seed(previous, target, 1, 64, 0, 1, 4, 4);
        if (target[1 * 4 + 0] != PadMapTileKind.GRASS || target[1 * 4 + 1] != PadMapTileKind.WATER) {
            throw new AssertionError("overlapping known tiles should be shifted into target snapshot");
        }
    }

    private static void doesNotCopyUnknownTiles() {
        PadMapTileKind[] previousTiles = unknownTiles(2, 2);
        previousTiles[0] = PadMapTileKind.UNKNOWN;
        PadMapSnapshot previous = new PadMapSnapshot(0, 64, 0, 1, 2, 2, previousTiles, 1.0F);
        PadMapTileKind[] target = filledTiles(2, 2, PadMapTileKind.TREE);
        PadMapSnapshotSeeder.seed(previous, target, 0, 64, 0, 1, 2, 2);
        if (target[0] != PadMapTileKind.TREE) {
            throw new AssertionError("UNKNOWN previous tiles should not overwrite target tiles");
        }
    }

    private static void rejectsMismatchedFloorOrCellSize() {
        PadMapTileKind[] previousTiles = filledTiles(2, 2, PadMapTileKind.GRASS);
        PadMapSnapshot previous = new PadMapSnapshot(0, 64, 0, 1, 2, 2, previousTiles, 1.0F);
        PadMapTileKind[] target = unknownTiles(2, 2);
        PadMapSnapshotSeeder.seed(previous, target, 0, 65, 0, 1, 2, 2);
        PadMapSnapshotSeeder.seed(previous, target, 0, 64, 0, 2, 2, 2);
        for (PadMapTileKind tile : target) {
            if (tile != PadMapTileKind.UNKNOWN) {
                throw new AssertionError("mismatched floor or cell size should not seed target tiles");
            }
        }
    }

    private static PadMapTileKind[] unknownTiles(int width, int height) {
        return filledTiles(width, height, PadMapTileKind.UNKNOWN);
    }

    private static PadMapTileKind[] filledTiles(int width, int height, PadMapTileKind kind) {
        PadMapTileKind[] tiles = new PadMapTileKind[width * height];
        java.util.Arrays.fill(tiles, kind);
        return tiles;
    }
}
