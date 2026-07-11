package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapTileKind;

/** Lightweight tests for rendered map overlap reuse. */
public final class PadMapRenderReusePlanSelfTest {
    private PadMapRenderReusePlanSelfTest() {
    }

    public static void main(String[] args) {
        reusesIntegerCellMovement();
        rejectsIncompatibleSnapshots();
        System.out.println("PadMapRenderReusePlanSelfTest passed");
    }

    private static void reusesIntegerCellMovement() {
        PadMapSnapshot previous = snapshot(0, 0, 1, 32, 16);
        PadMapRenderReusePlan.Plan plan = PadMapRenderReusePlan.between(previous, snapshot(4, 3, 1, 32, 16));
        if (!plan.reusable() || plan.textureCellShiftX() != 4 || plan.textureCellShiftY() != -3) {
            throw new AssertionError("snapshot movement should map to mirrored texture overlap shift: " + plan);
        }
    }

    private static void rejectsIncompatibleSnapshots() {
        PadMapSnapshot previous = snapshot(0, 0, 1, 32, 16);
        if (PadMapRenderReusePlan.between(previous, snapshot(0, 0, 2, 32, 16)).reusable()
                || PadMapRenderReusePlan.between(previous, snapshot(64, 0, 1, 32, 16)).reusable()) {
            throw new AssertionError("different scale or non-overlapping snapshots must use a full bake");
        }
    }

    private static PadMapSnapshot snapshot(int centerX, int centerZ, int cellSize, int width, int height) {
        PadMapTileKind[] tiles = new PadMapTileKind[width * height];
        java.util.Arrays.fill(tiles, PadMapTileKind.GRASS);
        return new PadMapSnapshot(centerX, 64, centerZ, cellSize, width, height, tiles, 1.0F);
    }
}