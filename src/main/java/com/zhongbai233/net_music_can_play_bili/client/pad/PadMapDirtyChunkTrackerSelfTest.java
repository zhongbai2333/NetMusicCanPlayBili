package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.List;

/** Lightweight self tests for dirty chunk tracking; no Minecraft runtime required. */
public final class PadMapDirtyChunkTrackerSelfTest {
    private PadMapDirtyChunkTrackerSelfTest() {
    }

    public static void main(String[] args) {
        drainsOnlyRequestedDimension();
        respectsDrainBudget();
        trimsToLimit();
        ignoresBlankDimensions();
        clearsPendingChunks();
        System.out.println("PadMapDirtyChunkTrackerSelfTest passed");
    }

    private static void drainsOnlyRequestedDimension() {
        PadMapDirtyChunkTracker tracker = new PadMapDirtyChunkTracker(8);
        tracker.mark("overworld", 1, 2);
        tracker.mark("nether", 3, 4);
        List<PadMapDirtyChunkTracker.Key> drained = tracker.drainForDimension("overworld", 8);
        if (drained.size() != 1 || drained.get(0).chunkX() != 1 || drained.get(0).chunkZ() != 2) {
            throw new AssertionError("drain should return only matching dimension chunks");
        }
        if (tracker.size() != 1) {
            throw new AssertionError("non-matching dimension chunk should remain queued");
        }
    }

    private static void respectsDrainBudget() {
        PadMapDirtyChunkTracker tracker = new PadMapDirtyChunkTracker(8);
        tracker.mark("overworld", 1, 1);
        tracker.mark("overworld", 2, 2);
        tracker.mark("overworld", 3, 3);
        List<PadMapDirtyChunkTracker.Key> drained = tracker.drainForDimension("overworld", 2);
        if (drained.size() != 2 || tracker.size() != 1) {
            throw new AssertionError("drain should respect max count and leave the rest queued");
        }
    }

    private static void trimsToLimit() {
        PadMapDirtyChunkTracker tracker = new PadMapDirtyChunkTracker(2);
        tracker.mark("overworld", 1, 1);
        tracker.mark("overworld", 2, 2);
        tracker.mark("overworld", 3, 3);
        if (tracker.size() != 2) {
            throw new AssertionError("tracker should trim to configured limit");
        }
        List<PadMapDirtyChunkTracker.Key> drained = tracker.drainForDimension("overworld", 8);
        if (drained.size() != 2 || drained.get(0).chunkX() != 2 || drained.get(1).chunkX() != 3) {
            throw new AssertionError("tracker should evict oldest dirty chunks first");
        }
    }

    private static void ignoresBlankDimensions() {
        PadMapDirtyChunkTracker tracker = new PadMapDirtyChunkTracker(2);
        tracker.mark("", 1, 1);
        tracker.mark(null, 2, 2);
        if (tracker.size() != 0 || !tracker.drainForDimension("", 1).isEmpty()) {
            throw new AssertionError("blank dimensions should be ignored");
        }
    }

    private static void clearsPendingChunks() {
        PadMapDirtyChunkTracker tracker = new PadMapDirtyChunkTracker(4);
        tracker.mark("overworld", 1, 1);
        tracker.mark("nether", 2, 2);
        tracker.clear();
        if (tracker.size() != 0) {
            throw new AssertionError("clear should remove pending chunks from every dimension");
        }
    }
}
