package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PadMapSnapshotComposerTest {
    @Test
    void copiesTileBufferWhenComposing() {
        PadMapSnapshotComposer composer = new PadMapSnapshotComposer(2.0F);
        PadMapTileKind[] tiles = { PadMapTileKind.GRASS, PadMapTileKind.WATER };
        PadMapSnapshot snapshot = composer.compose(1, 64, 2, 1, 2, 1, tiles, PadMapViewProfile.OUTDOOR);
        tiles[0] = PadMapTileKind.TREE;
        assertEquals(PadMapTileKind.GRASS, snapshot.tiles()[0]);
    }

    @Test
    void appliesIndoorDisplayScaleOnlyForIndoorSnapshots() {
        PadMapSnapshotComposer composer = new PadMapSnapshotComposer(2.5F);
        PadMapTileKind[] tiles = { PadMapTileKind.UNKNOWN };
        PadMapSnapshot indoor = composer.compose(0, 64, 0, 1, 1, 1, tiles, PadMapViewProfile.INDOOR);
        PadMapSnapshot outdoor = composer.compose(0, 64, 0, 1, 1, 1, tiles, PadMapViewProfile.OUTDOOR);
        assertEquals(2.5F, indoor.displayScale());
        assertEquals(1.0F, outdoor.displayScale());
    }
}