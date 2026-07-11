package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapTileKind;

/**
 * Lightweight self tests for Pad map bake scheduling; no Minecraft runtime
 * required.
 */
public final class PadMapBakeSchedulerSelfTest {
    private PadMapBakeSchedulerSelfTest() {
    }

    public static void main(String[] args) {
        bakesFirstSnapshot();
        skipsSameContentSignature();
        throttlesContentOnlyChangesButKeepsPending();
        layoutChangeBypassesThrottle();
        keepsProjectionOnLastBakedSnapshotUntilNextBakeCompletes();
        System.out.println("PadMapBakeSchedulerSelfTest passed");
    }

    private static void bakesFirstSnapshot() {
        PadMapBakeScheduler scheduler = new PadMapBakeScheduler(500L);
        PadMapSnapshot first = snapshot(0, 0, 1, PadMapTileKind.GRASS, 0L, 0L);
        PadMapBakeScheduler.BakeDecision decision = scheduler.request(first, 1_000L);
        assertBake(decision, first, "first snapshot should bake immediately");
        scheduler.complete(decision.snapshot(), decision.signature(), 1_010L);
        if (scheduler.renderedSnapshotOr(first) != first) {
            throw new AssertionError("completed snapshot should be reused for matching content signature");
        }
    }

    private static void skipsSameContentSignature() {
        PadMapBakeScheduler scheduler = new PadMapBakeScheduler(500L);
        PadMapSnapshot first = snapshot(0, 0, 1, PadMapTileKind.GRASS, 101L, 201L);
        PadMapBakeScheduler.BakeDecision firstDecision = scheduler.request(first, 1_000L);
        scheduler.complete(firstDecision.snapshot(), firstDecision.signature(), 1_010L);
        PadMapSnapshot equivalent = snapshot(10, 10, 1, PadMapTileKind.WATER, 101L, 999L);
        if (scheduler.request(equivalent, 2_000L).shouldBake()) {
            throw new AssertionError("same content signature should skip baking");
        }
        if (scheduler.renderedSnapshotOr(equivalent) != first) {
            throw new AssertionError("same content signature should use already rendered snapshot");
        }
    }

    private static void throttlesContentOnlyChangesButKeepsPending() {
        PadMapBakeScheduler scheduler = new PadMapBakeScheduler(500L);
        PadMapSnapshot first = snapshot(0, 0, 1, PadMapTileKind.GRASS, 101L, 201L);
        PadMapBakeScheduler.BakeDecision firstDecision = scheduler.request(first, 1_000L);
        scheduler.complete(firstDecision.snapshot(), firstDecision.signature(), 1_010L);
        PadMapSnapshot changed = snapshot(0, 0, 1, PadMapTileKind.WATER, 102L, 201L);
        if (scheduler.request(changed, 1_100L).shouldBake()) {
            throw new AssertionError("content-only change should be throttled inside min interval");
        }
        PadMapBakeScheduler.BakeDecision delayed = scheduler.request(changed, 1_600L);
        assertBake(delayed, changed, "pending content-only change should bake after min interval");
    }

    private static void layoutChangeBypassesThrottle() {
        PadMapBakeScheduler scheduler = new PadMapBakeScheduler(500L);
        PadMapSnapshot first = snapshot(0, 0, 1, PadMapTileKind.GRASS, 101L, 201L);
        PadMapBakeScheduler.BakeDecision firstDecision = scheduler.request(first, 1_000L);
        scheduler.complete(firstDecision.snapshot(), firstDecision.signature(), 1_010L);
        PadMapSnapshot layoutChanged = snapshot(0, 0, 1, PadMapTileKind.WATER, 102L, 202L);
        PadMapBakeScheduler.BakeDecision decision = scheduler.request(layoutChanged, 1_100L);
        assertBake(decision, layoutChanged, "layout change should bypass content bake throttle");
    }

    private static void keepsProjectionOnLastBakedSnapshotUntilNextBakeCompletes() {
        PadMapBakeScheduler scheduler = new PadMapBakeScheduler(500L);
        PadMapSnapshot first = snapshot(0, 0, 1, PadMapTileKind.GRASS, 101L, 201L);
        PadMapBakeScheduler.BakeDecision firstDecision = scheduler.request(first, 1_000L);
        scheduler.complete(firstDecision.snapshot(), firstDecision.signature(), 1_010L);
        PadMapSnapshot moved = snapshot(64, 0, 1, PadMapTileKind.WATER, 102L, 202L);
        PadMapBakeScheduler.BakeDecision movedDecision = scheduler.request(moved, 1_100L);
        assertBake(movedDecision, moved, "layout-changed moved snapshot should request a bake");
        if (scheduler.renderedSnapshotOr(moved) != first) {
            throw new AssertionError("projection should stay on the last baked snapshot until the new bake completes");
        }
        scheduler.complete(movedDecision.snapshot(), movedDecision.signature(), 1_120L);
        if (scheduler.renderedSnapshotOr(moved) != moved) {
            throw new AssertionError("projection should switch after the moved snapshot has been baked");
        }
    }

    private static PadMapSnapshot snapshot(int centerX, int centerZ, int cellSize, PadMapTileKind kind,
            long contentSignature, long layoutSignature) {
        PadMapTileKind[] tiles = { kind, kind, kind, kind };
        return new PadMapSnapshot(centerX, 64, centerZ, cellSize, 2, 2, tiles, 1.0F, contentSignature,
                layoutSignature);
    }

    private static void assertBake(PadMapBakeScheduler.BakeDecision decision, PadMapSnapshot expected,
            String message) {
        if (!decision.shouldBake() || decision.snapshot() != expected) {
            throw new AssertionError(message);
        }
    }
}
