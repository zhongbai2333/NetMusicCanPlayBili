package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapTileKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadMapBakeSchedulerTest {
    @Test
    void bakesFirstSnapshot() {
        PadMapBakeScheduler scheduler = new PadMapBakeScheduler(500L);
        PadMapSnapshot first = snapshot(0, 0, PadMapTileKind.GRASS, 0L, 0L);
        PadMapBakeScheduler.BakeDecision decision = scheduler.request(first, 1_000L);
        assertBake(decision, first);
        scheduler.complete(decision.snapshot(), decision.signature(), 1_010L);
        assertSame(first, scheduler.renderedSnapshotOr(first));
    }

    @Test
    void skipsSameContentSignature() {
        PadMapBakeScheduler scheduler = new PadMapBakeScheduler(500L);
        PadMapSnapshot first = snapshot(0, 0, PadMapTileKind.GRASS, 101L, 201L);
        PadMapBakeScheduler.BakeDecision firstDecision = scheduler.request(first, 1_000L);
        scheduler.complete(firstDecision.snapshot(), firstDecision.signature(), 1_010L);
        PadMapSnapshot equivalent = snapshot(10, 10, PadMapTileKind.WATER, 101L, 999L);
        assertFalse(scheduler.request(equivalent, 2_000L).shouldBake());
        assertSame(first, scheduler.renderedSnapshotOr(equivalent));
    }

    @Test
    void throttlesContentOnlyChangesButKeepsPending() {
        PadMapBakeScheduler scheduler = completedScheduler();
        PadMapSnapshot changed = snapshot(0, 0, PadMapTileKind.WATER, 102L, 201L);
        assertFalse(scheduler.request(changed, 1_100L).shouldBake());
        assertBake(scheduler.request(changed, 1_600L), changed);
    }

    @Test
    void layoutChangeBypassesThrottle() {
        PadMapBakeScheduler scheduler = completedScheduler();
        PadMapSnapshot changed = snapshot(0, 0, PadMapTileKind.WATER, 102L, 202L);
        assertBake(scheduler.request(changed, 1_100L), changed);
    }

    @Test
    void keepsProjectionOnLastBakedSnapshotUntilNextBakeCompletes() {
        PadMapBakeScheduler scheduler = new PadMapBakeScheduler(500L);
        PadMapSnapshot first = snapshot(0, 0, PadMapTileKind.GRASS, 101L, 201L);
        PadMapBakeScheduler.BakeDecision firstDecision = scheduler.request(first, 1_000L);
        scheduler.complete(firstDecision.snapshot(), firstDecision.signature(), 1_010L);
        PadMapSnapshot moved = snapshot(64, 0, PadMapTileKind.WATER, 102L, 202L);
        PadMapBakeScheduler.BakeDecision movedDecision = scheduler.request(moved, 1_100L);
        assertSame(first, scheduler.renderedSnapshotOr(moved));
        scheduler.complete(movedDecision.snapshot(), movedDecision.signature(), 1_120L);
        assertSame(moved, scheduler.renderedSnapshotOr(moved));
    }

    private static PadMapBakeScheduler completedScheduler() {
        PadMapBakeScheduler scheduler = new PadMapBakeScheduler(500L);
        PadMapSnapshot first = snapshot(0, 0, PadMapTileKind.GRASS, 101L, 201L);
        PadMapBakeScheduler.BakeDecision decision = scheduler.request(first, 1_000L);
        scheduler.complete(decision.snapshot(), decision.signature(), 1_010L);
        return scheduler;
    }

    private static PadMapSnapshot snapshot(int centerX, int centerZ, PadMapTileKind kind,
            long contentSignature, long layoutSignature) {
        PadMapTileKind[] tiles = { kind, kind, kind, kind };
        return new PadMapSnapshot(centerX, 64, centerZ, 1, 2, 2, tiles, 1.0F, contentSignature, layoutSignature);
    }

    private static void assertBake(PadMapBakeScheduler.BakeDecision decision, PadMapSnapshot expected) {
        assertTrue(decision.shouldBake());
        assertSame(expected, decision.snapshot());
    }
}