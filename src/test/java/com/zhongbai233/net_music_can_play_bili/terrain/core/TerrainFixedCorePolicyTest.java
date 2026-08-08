package com.zhongbai233.net_music_can_play_bili.terrain.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainFixedCorePolicyTest {
    @Test
    void retentionIsSolidInsideAndEmptyOutsideFixedSphere() {
        assertEquals(1.0D, TerrainFixedCorePolicy.retention(0.5D, 0.5D, 0.5D, 0, 0, 0));
        assertEquals(1.0D, TerrainFixedCorePolicy.retention(0.5D, 0.5D, 0.5D, 9, 0, 0));
        assertEquals(0.0D, TerrainFixedCorePolicy.retention(0.5D, 0.5D, 0.5D, 13, 0, 0));
        double shell = TerrainFixedCorePolicy.retention(0.5D, 0.5D, 0.5D, 11, 0, 0);
        assertTrue(shell > 0.0D && shell < 1.0D);
    }

    @Test
    void ditheringAndBranchesAreStableAndBounded() {
        boolean first = TerrainFixedCorePolicy.rendersBlock(42L, 0.5D, 0.5D, 0.5D, 11, 2, -1);
        assertEquals(first, TerrainFixedCorePolicy.rendersBlock(42L, 0.5D, 0.5D, 0.5D, 11, 2, -1));
        assertTrue(TerrainFixedCorePolicy.rendersBlock(42L, 0.5D, 0.5D, 0.5D, 0, 0, 0));
        assertFalse(TerrainFixedCorePolicy.rendersBlock(42L, 0.5D, 0.5D, 0.5D, 20, 0, 0));
        double length = TerrainFixedCorePolicy.branchLength(42L, -4, 8, 12, 2);
        assertTrue(length >= 0.5D && length <= 2.5D);
        assertEquals(TerrainFixedCorePolicy.emitsBranch(42L, -4, 8, 12, 2),
                TerrainFixedCorePolicy.emitsBranch(42L, -4, 8, 12, 2));
        assertEquals(4, TerrainFixedCorePolicy.overviewCellSize(20.0D));
        assertEquals(8, TerrainFixedCorePolicy.overviewCellSize(50.0D));
        assertEquals(16, TerrainFixedCorePolicy.overviewCellSize(80.0D));
        assertEquals(16, TerrainFixedCorePolicy.overviewCellSize(Double.NaN));
        assertTrue(TerrainFixedCorePolicy.sectionMayContainDetail(0.5D, 0.5D, 0.5D, 0, 0, 0));
        assertTrue(TerrainFixedCorePolicy.sectionMayContainDetail(16.5D, 0.5D, 0.5D, 16, 0, 0));
        assertFalse(TerrainFixedCorePolicy.sectionMayContainDetail(0.5D, 0.5D, 0.5D, 32, 0, 0));
    }
}