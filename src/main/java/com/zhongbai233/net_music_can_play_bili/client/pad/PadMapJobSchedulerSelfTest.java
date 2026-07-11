package com.zhongbai233.net_music_can_play_bili.client.pad;

/**
 * Lightweight self tests for Pad map job scheduling policy that do not need
 * Minecraft runtime.
 */
public final class PadMapJobSchedulerSelfTest {
    private PadMapJobSchedulerSelfTest() {
    }

    public static void main(String[] args) {
        choosesChunkBudgetByLag();
        cancelsExcessiveMovementLag();
        decidesStartReasons();
        decidesSeedReuse();
        System.out.println("PadMapJobSchedulerSelfTest passed");
    }

    private static void choosesChunkBudgetByLag() {
        PadMapJobScheduler scheduler = new PadMapJobScheduler(16, 8, 3, 2, 12, 32);
        if (scheduler.chunksPerTick(null, 0, 0) != 12) {
            throw new AssertionError("missing job should use normal chunk budget");
        }
        if (scheduler.chunksPerTick(jobAt(0, 0), 16, 0) != 12) {
            throw new AssertionError("nearby job should use normal chunk budget");
        }
        if (scheduler.chunksPerTick(jobAt(0, 0), 32, 0) != 31) {
            throw new AssertionError("resample-distance lag should use near-fast chunk budget");
        }
        if (scheduler.chunksPerTick(jobAt(0, 0), 64, 0) != 32) {
            throw new AssertionError("large lag should use fast chunk budget");
        }
    }

    private static void cancelsExcessiveMovementLag() {
        PadMapJobScheduler scheduler = new PadMapJobScheduler(16, 8, 3, 2, 12, 32);
        if (scheduler.shouldCancel(jobAt(0, 0), 47, outdoorLayerY(), 0, PadMapViewProfile.OUTDOOR, 1.25F)) {
            throw new AssertionError("movement below max job lag should keep the active job");
        }
        if (!scheduler.shouldCancel(jobAt(0, 0), 48, outdoorLayerY(), 0, PadMapViewProfile.OUTDOOR, 1.25F)) {
            throw new AssertionError("movement at max job lag should cancel the stale job");
        }
        if (!scheduler.shouldCancel(jobAt(0, 0), 0, 64, 0, PadMapViewProfile.INDOOR, 1.25F)) {
            throw new AssertionError("profile or floor changes should still cancel stale jobs");
        }
    }

    private static void decidesStartReasons() {
        PadMapJobScheduler scheduler = new PadMapJobScheduler(16, 8, 3, 2, 12, 32);
        if (!scheduler.shouldStart(null, PadMapViewProfile.OUTDOOR, 0, outdoorLayerY(), 0,
                PadMapViewProfile.OUTDOOR)) {
            throw new AssertionError("no completed map should start a job");
        }
        PadMapSnapshot completed = snapshot(0, outdoorLayerY(), 0, 1.25F);
        if (scheduler.shouldStart(completed, PadMapViewProfile.OUTDOOR, 15, outdoorLayerY(), 0,
                PadMapViewProfile.OUTDOOR)) {
            throw new AssertionError("movement below outdoor recenter threshold should not start");
        }
        if (!scheduler.shouldStart(completed, PadMapViewProfile.OUTDOOR, 16, outdoorLayerY(), 0,
                PadMapViewProfile.OUTDOOR)) {
            throw new AssertionError("movement at outdoor recenter threshold should start");
        }
        if (!scheduler.shouldStart(completed, PadMapViewProfile.OUTDOOR, 0, 64, 0, PadMapViewProfile.INDOOR)) {
            throw new AssertionError("profile/floor change should start");
        }
    }

    private static void decidesSeedReuse() {
        PadMapJobScheduler scheduler = new PadMapJobScheduler(16, 8, 3, 2, 12, 32);
        PadMapSnapshot completed = snapshot(0, outdoorLayerY(), 0, 1.25F);
        if (!scheduler.canSeedPrevious(completed, PadMapViewProfile.OUTDOOR, PadMapViewProfile.OUTDOOR, 1.25F)) {
            throw new AssertionError("matching profile and zoom should seed previous snapshot");
        }
        if (scheduler.canSeedPrevious(completed, PadMapViewProfile.OUTDOOR, PadMapViewProfile.INDOOR, 1.25F)) {
            throw new AssertionError("different profile should not seed previous snapshot");
        }
        if (scheduler.canSeedPrevious(completed, PadMapViewProfile.OUTDOOR, PadMapViewProfile.OUTDOOR, 0.5F)) {
            throw new AssertionError("different cell size should not seed previous snapshot");
        }
    }

    private static PadMapClientCache.Job jobAt(int centerX, int centerZ) {
        return new PadMapClientCache.Job(null, centerX, centerZ, outdoorLayerY(), PadMapViewProfile.OUTDOOR, 1.25F,
                null);
    }

    private static PadMapSnapshot snapshot(int centerX, int centerY, int centerZ, float zoom) {
        int cellSize = PadMapSampler.cellSizeForZoom(zoom);
        PadMapTileKind[] tiles = { PadMapTileKind.UNKNOWN };
        return new PadMapSnapshot(centerX, centerY, centerZ, cellSize, 1, 1, tiles, 1.0F);
    }

    private static int outdoorLayerY() {
        return PadMapViewProfileStabilizer.OUTDOOR_LAYER_Y;
    }
}
