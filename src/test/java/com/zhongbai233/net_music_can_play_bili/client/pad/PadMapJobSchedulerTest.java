package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadMapJobSchedulerTest {
    private final PadMapJobScheduler scheduler = new PadMapJobScheduler(16, 8, 3, 2, 12, 32);

    @Test
    void choosesChunkBudgetByLag() {
        assertEquals(12, scheduler.chunksPerTick(null, 0, 0));
        assertEquals(12, scheduler.chunksPerTick(jobAt(0, 0), 16, 0));
        assertEquals(31, scheduler.chunksPerTick(jobAt(0, 0), 32, 0));
        assertEquals(32, scheduler.chunksPerTick(jobAt(0, 0), 64, 0));
    }

    @Test
    void cancelsExcessiveMovementLag() {
        assertFalse(scheduler.shouldCancel(jobAt(0, 0), 47, outdoorLayerY(), 0,
                PadMapViewProfile.OUTDOOR, 1.25F));
        assertTrue(scheduler.shouldCancel(jobAt(0, 0), 48, outdoorLayerY(), 0,
                PadMapViewProfile.OUTDOOR, 1.25F));
        assertTrue(scheduler.shouldCancel(jobAt(0, 0), 0, 64, 0,
                PadMapViewProfile.INDOOR, 1.25F));
    }

    @Test
    void decidesStartReasons() {
        assertTrue(scheduler.shouldStart(null, PadMapViewProfile.OUTDOOR, 0, outdoorLayerY(), 0,
                PadMapViewProfile.OUTDOOR));
        PadMapSnapshot completed = snapshot(0, outdoorLayerY(), 0, 1.25F);
        assertFalse(scheduler.shouldStart(completed, PadMapViewProfile.OUTDOOR, 15, outdoorLayerY(), 0,
                PadMapViewProfile.OUTDOOR));
        assertTrue(scheduler.shouldStart(completed, PadMapViewProfile.OUTDOOR, 16, outdoorLayerY(), 0,
                PadMapViewProfile.OUTDOOR));
        assertTrue(scheduler.shouldStart(completed, PadMapViewProfile.OUTDOOR, 0, 64, 0,
                PadMapViewProfile.INDOOR));
    }

    @Test
    void decidesSeedReuse() {
        PadMapSnapshot completed = snapshot(0, outdoorLayerY(), 0, 1.25F);
        assertTrue(scheduler.canSeedPrevious(
                completed, PadMapViewProfile.OUTDOOR, PadMapViewProfile.OUTDOOR, 1.25F));
        assertFalse(scheduler.canSeedPrevious(
                completed, PadMapViewProfile.OUTDOOR, PadMapViewProfile.INDOOR, 1.25F));
        assertFalse(scheduler.canSeedPrevious(
                completed, PadMapViewProfile.OUTDOOR, PadMapViewProfile.OUTDOOR, 0.5F));
    }

    private static PadMapJobScheduler.JobView jobAt(int centerX, int centerZ) {
        return new PadMapJobScheduler.JobView(
                centerX, centerZ, outdoorLayerY(), PadMapViewProfile.OUTDOOR, 1.25F);
    }

    private static PadMapSnapshot snapshot(int centerX, int centerY, int centerZ, float zoom) {
        int cellSize = PadMapSamplingPolicy.cellSizeForZoom(zoom);
        return new PadMapSnapshot(centerX, centerY, centerZ, cellSize, 1, 1,
                new PadMapTileKind[] { PadMapTileKind.UNKNOWN }, 1.0F);
    }

    private static int outdoorLayerY() {
        return PadMapViewProfileStabilizer.OUTDOOR_LAYER_Y;
    }
}