package com.zhongbai233.net_music_can_play_bili.terrain.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainCoreSafetyTest {
    @Test
    void samplingMeterStopsAtEveryHardLimit() {
        AtomicLong clock = new AtomicLong(100L);
        TerrainSamplingBudget.Meter meter = new TerrainSamplingBudget(2, 1, 50L).start(clock::get);
        assertTrue(meter.tryAcquireSection());
        assertFalse(meter.tryAcquireSection());
        assertTrue(meter.tryAcquireCell());
        assertTrue(meter.tryAcquireCell());
        assertFalse(meter.tryAcquireCell());

        TerrainSamplingBudget.Meter timed = new TerrainSamplingBudget(100, 10, 50L).start(clock::get);
        clock.addAndGet(50L);
        assertFalse(timed.tryAcquireCell());
        assertTrue(timed.exhausted());
    }

    @Test
    void dirtyTrackerInvalidatesBoundaryNeighborsAtNegativeCoordinates() {
        TerrainDirtyTracker tracker = new TerrainDirtyTracker(16);
        tracker.markBlockAndBoundaryNeighbors(-16, 31, 0);
        var dirty = tracker.drain(16);
        assertTrue(dirty.contains(new TerrainSectionKey(-1, 1, 0)));
        assertTrue(dirty.contains(new TerrainSectionKey(-2, 1, 0)));
        assertTrue(dirty.contains(new TerrainSectionKey(-1, 2, 0)));
        assertTrue(dirty.contains(new TerrainSectionKey(-1, 1, -1)));
        assertEquals(4, dirty.size());

        TerrainDirtyTracker bounded = new TerrainDirtyTracker(2);
        bounded.markSection(new TerrainSectionKey(0, 0, 0));
        bounded.markSection(new TerrainSectionKey(1, 0, 0));
        bounded.markSection(new TerrainSectionKey(2, 0, 0));
        assertEquals(2, bounded.size());
        assertFalse(bounded.drain(2).contains(new TerrainSectionKey(0, 0, 0)));
    }

    @Test
    void weightedCacheNeverExceedsByteBudgetAndUsesLruOrder() {
        WeightedLruCache<String, byte[]> cache = new WeightedLruCache<>(10L, bytes -> bytes.length);
        assertTrue(cache.put("a", new byte[4]));
        assertTrue(cache.put("b", new byte[4]));
        cache.get("a");
        assertTrue(cache.put("c", new byte[4]));
        assertTrue(cache.get("a").isPresent());
        assertTrue(cache.get("b").isEmpty());
        assertTrue(cache.get("c").isPresent());
        assertEquals(8L, cache.totalWeight());

        assertFalse(cache.put("huge", new byte[11]));
        assertEquals(8L, cache.totalWeight());
        assertTrue(cache.put("a", new byte[11]) == false);
        assertEquals(4, cache.get("a").orElseThrow().length);
    }

    @Test
    void lodUsesHysteresisUnknownAndMemoryPressureDegradation() {
        TerrainLodPolicy policy = new TerrainLodPolicy(16.0D, 48.0D, 2.0D);
        assertEquals(TerrainLodLevel.UNKNOWN,
                policy.choose(1.0D, TerrainLodLevel.NEAR, false, false, 0.0D));
        assertEquals(TerrainLodLevel.NEAR,
                policy.choose(17.0D, TerrainLodLevel.NEAR, true, false, 0.0D));
        assertEquals(TerrainLodLevel.MID,
                policy.choose(19.0D, TerrainLodLevel.NEAR, true, false, 0.0D));
        assertEquals(TerrainLodLevel.NEAR,
                policy.choose(100.0D, TerrainLodLevel.FAR, true, true, 1.0D));
        assertEquals(TerrainLodLevel.FAR,
                policy.choose(20.0D, TerrainLodLevel.MID, true, false, 0.95D));
    }

    @Test
    void boundsUseInclusiveLongVolumeAndSectionIntersection() {
        TerrainBounds bounds = new TerrainBounds(-64, -32, -64, 63, 31, 63);
        assertEquals(1_048_576L, bounds.volume());
        assertTrue(bounds.intersects(new TerrainSectionKey(-4, -2, -4)));
        assertTrue(bounds.intersects(new TerrainSectionKey(3, 1, 3)));
        assertFalse(bounds.intersects(new TerrainSectionKey(4, 1, 3)));
        assertEquals(8_589_934_592L,
            new TerrainBounds(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 1, 0).volume());
        org.junit.jupiter.api.Assertions.assertThrows(ArithmeticException.class,
            () -> new TerrainBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE).volume());
        }

        @Test
        void coverageCursorIsBudgetedNearestFirstAndDoesNotPreallocateRange() {
        TerrainBounds bounds = new TerrainBounds(-4096, -64, -4096, 4096, 319, 4096);
        TerrainCoverageCursor cursor = new TerrainCoverageCursor(bounds, new TerrainSectionKey(0, 4, 0));

        var first = cursor.next(7);
        assertEquals(7, first.size());
        assertTrue(first.stream().allMatch(key -> key.x() == 0 && key.z() == 0));
        assertEquals(new TerrainSectionKey(0, -4, 0), first.get(0));
        assertFalse(cursor.exhausted());

        TerrainCoverageCursor small = new TerrainCoverageCursor(
            new TerrainBounds(-16, 0, -16, 15, 31, 15), new TerrainSectionKey(0, 0, 0));
        var all = new java.util.ArrayList<TerrainSectionKey>();
        while (!small.exhausted()) {
            all.addAll(small.next(3));
        }
        assertEquals(8, all.size());
        assertEquals(all.size(), new HashSet<>(all).size());
    }

    @Test
    void sectionCaptureResumesAcrossTicksAndPublishesOnlyWhenComplete() {
        TerrainSectionCaptureJob job = new TerrainSectionCaptureJob(new TerrainSectionKey(1, -1, 2), 7L,
                (x, y, z) -> TerrainCellSample.air());
        int ticks = 0;
        while (!job.done()) {
            var result = job.step(new TerrainSamplingBudget(512, 1, Long.MAX_VALUE).start());
            assertTrue(result.sampledCells() <= 512);
            if (!result.completed()) {
                assertTrue(job.completedSnapshot().isEmpty());
            }
            ticks++;
        }
        assertEquals(8, ticks);
        TerrainSectionSnapshot snapshot = job.completedSnapshot().orElseThrow();
        assertEquals(7L, snapshot.generation());
        assertEquals(TerrainCellSample.RenderCategory.AIR,
                snapshot.cell(15, 15, 15).renderCategory());
    }

            @Test
            void surfaceMesherRemovesInternalFacesAndHonorsFaceLimit() {
            var cells = new java.util.ArrayList<TerrainCellSample>(TerrainSectionKey.CELL_COUNT);
            for (int i = 0; i < TerrainSectionKey.CELL_COUNT; i++) {
                cells.add(TerrainCellSample.air());
            }
            cells.set(TerrainSectionSnapshot.index(1, 1, 1),
                new TerrainCellSample(TerrainCellSample.Availability.LOADED,
                    TerrainCellSample.RenderCategory.MODEL, "minecraft:stone", "", false, false));
            TerrainSectionSnapshot single = new TerrainSectionSnapshot(new TerrainSectionKey(0, 0, 0), 1L,
                cells, 4096L);
            TerrainSurfaceMesh singleMesh = new TerrainSurfaceMesher(100).mesh(single, TerrainLodLevel.NEAR);
            assertEquals(6, singleMesh.faces().size());
            assertFalse(singleMesh.truncated());

            cells.set(TerrainSectionSnapshot.index(2, 1, 1),
                new TerrainCellSample(TerrainCellSample.Availability.LOADED,
                    TerrainCellSample.RenderCategory.MODEL, "minecraft:stone", "", false, false));
            TerrainSectionSnapshot pair = new TerrainSectionSnapshot(new TerrainSectionKey(0, 0, 0), 2L,
                cells, 4096L);
        assertEquals(10, new TerrainSurfaceMesher(100).mesh(pair, TerrainLodLevel.NEAR).faces().size());
        TerrainSurfaceMesh limited = new TerrainSurfaceMesher(5).mesh(pair, TerrainLodLevel.NEAR);
        assertEquals(5, limited.faces().size());
        assertTrue(limited.truncated());
    }

    @Test
    void workPlannerDeduplicatesBoundsPrioritizesSelectionAndCapsPendingWork() {
        TerrainWorkPlanner planner = new TerrainWorkPlanner(3);
        planner.updateCamera(new TerrainSectionKey(0, 0, 0));
        planner.updateCoverage(new TerrainBounds(0, 0, 0, 47, 15, 15), TerrainLodLevel.FAR);
        planner.updateSelected(new TerrainSectionKey(2, 0, 0));
        planner.markDirty(new TerrainSectionKey(0, 0, 0), TerrainLodLevel.NEAR);

        assertEquals(3, planner.pending());
        var work = planner.nextWork(3);
        assertEquals(new TerrainSectionKey(2, 0, 0), work.get(0).section());
        assertEquals(TerrainWorkPriority.SELECTED_NEAR, work.get(0).priority());
        assertEquals(new TerrainSectionKey(0, 0, 0), work.get(1).section());
        assertEquals(TerrainWorkPriority.CAMERA_NEAR, work.get(1).priority());
        assertEquals(0, planner.pending());
    }
}