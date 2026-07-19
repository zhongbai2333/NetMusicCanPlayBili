package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PadMapSnapshotSeederTest {
    @Test
    void reusesOverlappingKnownTiles() {
        PadMapTileKind[] previousTiles = filledTiles(4, 4, PadMapTileKind.UNKNOWN);
        previousTiles[5] = PadMapTileKind.GRASS;
        previousTiles[6] = PadMapTileKind.WATER;
        PadMapSnapshot previous = new PadMapSnapshot(0, 64, 0, 1, 4, 4, previousTiles, 1.0F);
        PadMapTileKind[] target = filledTiles(4, 4, PadMapTileKind.UNKNOWN);

        PadMapSnapshotSeeder.seed(previous, target, 1, 64, 0, 1, 4, 4);

        assertEquals(PadMapTileKind.GRASS, target[4]);
        assertEquals(PadMapTileKind.WATER, target[5]);
    }

    @Test
    void doesNotCopyUnknownTiles() {
        PadMapSnapshot previous = new PadMapSnapshot(0, 64, 0, 1, 2, 2,
                filledTiles(2, 2, PadMapTileKind.UNKNOWN), 1.0F);
        PadMapTileKind[] target = filledTiles(2, 2, PadMapTileKind.TREE);
        PadMapSnapshotSeeder.seed(previous, target, 0, 64, 0, 1, 2, 2);
        assertEquals(PadMapTileKind.TREE, target[0]);
    }

    @Test
    void rejectsMismatchedFloorOrCellSize() {
        PadMapSnapshot previous = new PadMapSnapshot(0, 64, 0, 1, 2, 2,
                filledTiles(2, 2, PadMapTileKind.GRASS), 1.0F);
        PadMapTileKind[] target = filledTiles(2, 2, PadMapTileKind.UNKNOWN);
        PadMapSnapshotSeeder.seed(previous, target, 0, 65, 0, 1, 2, 2);
        PadMapSnapshotSeeder.seed(previous, target, 0, 64, 0, 2, 2, 2);
        for (PadMapTileKind tile : target) {
            assertEquals(PadMapTileKind.UNKNOWN, tile);
        }
    }

    private static PadMapTileKind[] filledTiles(int width, int height, PadMapTileKind kind) {
        PadMapTileKind[] tiles = new PadMapTileKind[width * height];
        Arrays.fill(tiles, kind);
        return tiles;
    }
}