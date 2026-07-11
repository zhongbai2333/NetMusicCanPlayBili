package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.List;

/** Lightweight tests for profile-specific completed map snapshots. */
public final class PadMapViewSnapshotCacheSelfTest {
    private PadMapViewSnapshotCacheSelfTest() {
    }

    public static void main(String[] args) {
        restoresOutdoorAfterIndoorRoundTrip();
        isolatesIndoorFloorsAndCellSizes();
        invalidatesInactiveSnapshots();
        System.out.println("PadMapViewSnapshotCacheSelfTest passed");
    }

    private static void restoresOutdoorAfterIndoorRoundTrip() {
        PadMapViewSnapshotCache cache = new PadMapViewSnapshotCache();
        PadMapSnapshot outdoor = snapshot(0, -64, 0, 1, PadMapTileKind.GRASS);
        PadMapSnapshot indoor = snapshot(0, 70, 0, 1, PadMapTileKind.INDOOR_FLOOR);
        cache.put(PadMapViewProfile.OUTDOOR, outdoor);
        cache.put(PadMapViewProfile.INDOOR, indoor);
        if (cache.get(PadMapViewProfile.OUTDOOR, -64, 1) != outdoor) {
            throw new AssertionError("outdoor snapshot must survive an indoor round trip");
        }
    }

    private static void isolatesIndoorFloorsAndCellSizes() {
        PadMapViewSnapshotCache cache = new PadMapViewSnapshotCache();
        PadMapSnapshot floor64 = snapshot(0, 64, 0, 1, PadMapTileKind.INDOOR_FLOOR);
        PadMapSnapshot floor80 = snapshot(0, 80, 0, 1, PadMapTileKind.BUILDING);
        cache.put(PadMapViewProfile.INDOOR, floor64);
        cache.put(PadMapViewProfile.INDOOR, floor80);
        if (cache.get(PadMapViewProfile.INDOOR, 64, 1) != floor64
                || cache.get(PadMapViewProfile.INDOOR, 80, 1) != floor80
                || cache.get(PadMapViewProfile.INDOOR, 64, 4) != null) {
            throw new AssertionError("floors and cell sizes must use independent snapshot slots");
        }
    }

    private static void invalidatesInactiveSnapshots() {
        PadMapViewSnapshotCache cache = new PadMapViewSnapshotCache();
        PadMapSnapshot outdoor = snapshot(8, -64, 8, 1, PadMapTileKind.GRASS);
        cache.put(PadMapViewProfile.OUTDOOR, outdoor);
        cache.invalidate(List.of(new PadMapDirtyChunkTracker.Key("overworld", 0, 0)));
        PadMapSnapshot invalidated = cache.get(PadMapViewProfile.OUTDOOR, -64, 1);
        if (invalidated == null || invalidated.tile(0, 0) != PadMapTileKind.UNKNOWN) {
            throw new AssertionError("dirty updates must invalidate inactive profile snapshots");
        }
    }

    private static PadMapSnapshot snapshot(int centerX, int centerY, int centerZ, int cellSize,
            PadMapTileKind kind) {
        PadMapTileKind[] tiles = new PadMapTileKind[16 * 16];
        java.util.Arrays.fill(tiles, kind);
        return new PadMapSnapshot(centerX, centerY, centerZ, cellSize, 16, 16, tiles, 1.0F);
    }
}