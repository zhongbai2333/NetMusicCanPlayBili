package com.zhongbai233.net_music_can_play_bili.client.pad;

/** Lightweight self tests for composing immutable Pad map snapshots. */
public final class PadMapSnapshotComposerSelfTest {
    private PadMapSnapshotComposerSelfTest() {
    }

    public static void main(String[] args) {
        copiesTileBufferWhenComposing();
        appliesIndoorDisplayScaleOnlyForIndoorSnapshots();
        System.out.println("PadMapSnapshotComposerSelfTest passed");
    }

    private static void copiesTileBufferWhenComposing() {
        PadMapSnapshotComposer composer = new PadMapSnapshotComposer(2.0F);
        PadMapTileKind[] tiles = new PadMapTileKind[] { PadMapTileKind.GRASS, PadMapTileKind.WATER };
        PadMapSnapshot snapshot = composer.compose(1, 64, 2, 1, 2, 1, tiles, PadMapViewProfile.OUTDOOR);
        tiles[0] = PadMapTileKind.TREE;
        if (snapshot.tiles()[0] != PadMapTileKind.GRASS) {
            throw new AssertionError("snapshot should copy mutable tile buffer");
        }
    }

    private static void appliesIndoorDisplayScaleOnlyForIndoorSnapshots() {
        PadMapSnapshotComposer composer = new PadMapSnapshotComposer(2.5F);
        PadMapTileKind[] tiles = new PadMapTileKind[] { PadMapTileKind.UNKNOWN };
        PadMapSnapshot indoor = composer.compose(0, 64, 0, 1, 1, 1, tiles, PadMapViewProfile.INDOOR);
        PadMapSnapshot outdoor = composer.compose(0, 64, 0, 1, 1, 1, tiles, PadMapViewProfile.OUTDOOR);
        if (indoor.displayScale() != 2.5F) {
            throw new AssertionError("indoor snapshot should use configured display scale");
        }
        if (outdoor.displayScale() != 1.0F) {
            throw new AssertionError("outdoor snapshot should use display scale 1.0");
        }
    }
}
