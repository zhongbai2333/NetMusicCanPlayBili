package com.zhongbai233.net_music_can_play_bili.client.renderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetMusicDiscModelAdapterTest {
    private static final double EPSILON = 1.0e-9D;

    @Test
    void rotationMatchesNetMusicFortyTickCycle() {
        assertEquals(0.0F, DiscRotationPolicy.rotationAt(0L, 0.0F), 1.0e-6F);
        assertEquals((float) (Math.PI * 0.5D),
            DiscRotationPolicy.rotationAt(10L, 0.0F), 1.0e-6F);
        assertEquals((float) Math.PI,
            DiscRotationPolicy.rotationAt(20L, 0.0F), 1.0e-6F);
        assertEquals(0.0F, DiscRotationPolicy.rotationAt(40L, 0.0F), 1.0e-6F);
    }

    @Test
    void rotationUsesClampedPartialTickAndHandlesNegativeGameTime() {
        float step = (float) (Math.PI / 20.0D);
        assertEquals(step, DiscRotationPolicy.rotationAt(0L, 2.0F), 1.0e-6F);
        assertEquals(39.0F * step, DiscRotationPolicy.rotationAt(-1L, -2.0F), 1.0e-6F);
    }

    @Test
    void discCenterRotatesAroundBlockCenterWithBodyModel() {
        assertCenter(0, 9.7449D, 7.7449D);
        assertCenter(1, 8.2551D, 9.7449D);
        assertCenter(2, 6.2551D, 8.2551D);
        assertCenter(3, 7.7449D, 6.2551D);
    }

    @Test
    void placementNormalizesQuarterTurns() {
        DiscPlacementPolicy.Placement north = DiscPlacementPolicy.forClockwiseQuarterTurns(0);
        assertEquals(north.anchorX(), DiscPlacementPolicy.forClockwiseQuarterTurns(4).anchorX(), EPSILON);
        assertEquals(north.anchorZ(), DiscPlacementPolicy.forClockwiseQuarterTurns(-4).anchorZ(), EPSILON);
    }

    private static void assertCenter(int quarterTurns, double expectedBlockbenchX, double expectedBlockbenchZ) {
        DiscPlacementPolicy.Placement placement = DiscPlacementPolicy.forClockwiseQuarterTurns(quarterTurns);
        assertEquals(expectedBlockbenchX, placement.anchorX() * DiscPlacementPolicy.MODEL_SCALE * 16.0D, EPSILON);
        assertEquals(expectedBlockbenchZ, placement.anchorZ() * DiscPlacementPolicy.MODEL_SCALE * 16.0D, EPSILON);
    }
}
