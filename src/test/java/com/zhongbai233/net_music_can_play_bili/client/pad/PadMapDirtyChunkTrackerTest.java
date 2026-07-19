package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadMapDirtyChunkTrackerTest {
    @Test
    void drainsOnlyRequestedDimension() {
        PadMapDirtyChunkTracker tracker = new PadMapDirtyChunkTracker(8);
        tracker.mark("overworld", 1, 2);
        tracker.mark("nether", 3, 4);

        List<PadMapDirtyChunkTracker.Key> drained = tracker.drainForDimension("overworld", 8);

        assertEquals(1, drained.size());
        assertEquals(1, drained.getFirst().chunkX());
        assertEquals(2, drained.getFirst().chunkZ());
        assertEquals(1, tracker.size());
    }

    @Test
    void respectsDrainBudget() {
        PadMapDirtyChunkTracker tracker = new PadMapDirtyChunkTracker(8);
        tracker.mark("overworld", 1, 1);
        tracker.mark("overworld", 2, 2);
        tracker.mark("overworld", 3, 3);

        assertEquals(2, tracker.drainForDimension("overworld", 2).size());
        assertEquals(1, tracker.size());
    }

    @Test
    void trimsToLimit() {
        PadMapDirtyChunkTracker tracker = new PadMapDirtyChunkTracker(2);
        tracker.mark("overworld", 1, 1);
        tracker.mark("overworld", 2, 2);
        tracker.mark("overworld", 3, 3);

        List<PadMapDirtyChunkTracker.Key> drained = tracker.drainForDimension("overworld", 8);

        assertEquals(2, drained.size());
        assertEquals(2, drained.get(0).chunkX());
        assertEquals(3, drained.get(1).chunkX());
    }

    @Test
    void ignoresBlankDimensions() {
        PadMapDirtyChunkTracker tracker = new PadMapDirtyChunkTracker(2);
        tracker.mark("", 1, 1);
        tracker.mark(null, 2, 2);
        assertEquals(0, tracker.size());
        assertTrue(tracker.drainForDimension("", 1).isEmpty());
    }

    @Test
    void clearsPendingChunks() {
        PadMapDirtyChunkTracker tracker = new PadMapDirtyChunkTracker(4);
        tracker.mark("overworld", 1, 1);
        tracker.mark("nether", 2, 2);
        tracker.clear();
        assertEquals(0, tracker.size());
    }
}