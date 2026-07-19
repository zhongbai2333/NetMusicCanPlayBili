package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapTileKind;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadMapRenderReusePlanTest {
    @Test
    void reusesIntegerCellMovement() {
        PadMapRenderReusePlan.Plan plan = PadMapRenderReusePlan.between(
                snapshot(0, 0, 1, 32, 16), snapshot(4, 3, 1, 32, 16));
        assertTrue(plan.reusable());
        assertEquals(4, plan.textureCellShiftX());
        assertEquals(-3, plan.textureCellShiftY());
    }

    @Test
    void rejectsIncompatibleSnapshots() {
        PadMapSnapshot previous = snapshot(0, 0, 1, 32, 16);
        assertFalse(PadMapRenderReusePlan.between(previous, snapshot(0, 0, 2, 32, 16)).reusable());
        assertFalse(PadMapRenderReusePlan.between(previous, snapshot(64, 0, 1, 32, 16)).reusable());
    }

    private static PadMapSnapshot snapshot(int centerX, int centerZ, int cellSize, int width, int height) {
        PadMapTileKind[] tiles = new PadMapTileKind[width * height];
        Arrays.fill(tiles, PadMapTileKind.GRASS);
        return new PadMapSnapshot(centerX, 64, centerZ, cellSize, width, height, tiles, 1.0F);
    }
}