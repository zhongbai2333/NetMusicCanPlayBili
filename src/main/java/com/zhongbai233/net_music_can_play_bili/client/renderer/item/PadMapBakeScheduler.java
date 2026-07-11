package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;

/**
 * Decides when a Pad map snapshot should be baked into the reusable base
 * texture.
 */
final class PadMapBakeScheduler {
    private final long minBakeIntervalNanos;
    private PadMapSnapshot lastSnapshot;
    private PadMapSnapshot pendingSnapshot;
    private long lastSnapshotSignature;
    private long pendingSnapshotSignature;
    private long lastLayoutSignature;
    private long lastBakeNanos;

    PadMapBakeScheduler(long minBakeIntervalNanos) {
        this.minBakeIntervalNanos = Math.max(0L, minBakeIntervalNanos);
    }

    PadMapSnapshot renderedSnapshotOr(PadMapSnapshot fallback) {
        if (lastSnapshot != null) {
            return lastSnapshot;
        }
        return fallback;
    }

    BakeDecision request(PadMapSnapshot snapshot, long nowNanos) {
        if (snapshot == null) {
            return BakeDecision.skip();
        }
        long signature = snapshot.contentSignature();
        if (snapshot == lastSnapshot || signature == lastSnapshotSignature) {
            return BakeDecision.skip();
        }
        long layoutSignature = snapshot.layoutSignature();
        boolean layoutChanged = layoutSignature != lastLayoutSignature;
        pendingSnapshot = snapshot;
        pendingSnapshotSignature = signature;
        if (!layoutChanged && lastSnapshot != null && nowNanos - lastBakeNanos < minBakeIntervalNanos) {
            return BakeDecision.skip();
        }
        return BakeDecision.bake(pendingSnapshot, pendingSnapshotSignature);
    }

    void complete(PadMapSnapshot snapshot, long signature, long completedNanos) {
        lastSnapshot = snapshot;
        lastSnapshotSignature = signature;
        lastLayoutSignature = snapshot.layoutSignature();
        pendingSnapshot = null;
        pendingSnapshotSignature = 0L;
        lastBakeNanos = completedNanos;
    }

    record BakeDecision(PadMapSnapshot snapshot, long signature) {
        boolean shouldBake() {
            return snapshot != null;
        }

        static BakeDecision skip() {
            return new BakeDecision(null, 0L);
        }

        static BakeDecision bake(PadMapSnapshot snapshot, long signature) {
            return new BakeDecision(snapshot, signature);
        }
    }
}
