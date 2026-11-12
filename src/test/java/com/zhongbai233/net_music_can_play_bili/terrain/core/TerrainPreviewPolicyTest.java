package com.zhongbai233.net_music_can_play_bili.terrain.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Terrain preview lighting, coordinates and deterministic detail selection. */
class TerrainPreviewPolicyTest {
    @Test
    void roundTripsEveryVanillaLightLevelPair() {
        for (int block = 0; block <= 15; block++) {
            for (int sky = 0; sky <= 15; sky++) {
                byte packed = TerrainPackedLight.pack(block, sky);
                assertEquals(block, TerrainPackedLight.block(packed));
                assertEquals(sky, TerrainPackedLight.sky(packed));
            }
        }
    }

    @Test
    void rejectsOutOfRangeLightLevels() {
        assertThrows(IllegalArgumentException.class, () -> TerrainPackedLight.pack(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> TerrainPackedLight.pack(0, 16));
    }

    @Test
    void preservesEachCapturedBiomeTint() {
        TerrainTintColors colors = new TerrainTintColors(
                0xFF55AA33, 0xFF448822, 0xFF998844, 0xFF3366CC);

        assertEquals(0xFF55AA33, colors.color(TerrainTintColors.TintType.GRASS));
        assertEquals(0xFF448822, colors.color(TerrainTintColors.TintType.FOLIAGE));
        assertEquals(0xFF998844, colors.color(TerrainTintColors.TintType.DRY_FOLIAGE));
        assertEquals(0xFF3366CC, colors.color(TerrainTintColors.TintType.WATER));
    }

    @Test
    void vanillaSectionLocalVerticesOnlyReceivePreviewHalfBlockOffset() {
        assertEquals(4.75F, TerrainFluidVertexCoordinates.previewX(5.25F));
        assertEquals(5.875F, TerrainFluidVertexCoordinates.previewY(5.875F));
        assertEquals(5.0F, TerrainFluidVertexCoordinates.previewZ(5.5F));
    }

    @Test
    void sectionOriginMatchesBlockModelHalfBlockConvention() {
        assertEquals(-0.5F, TerrainFluidVertexCoordinates.previewX(0.0F));
        assertEquals(0.0F, TerrainFluidVertexCoordinates.previewY(0.0F));
        assertEquals(-0.5F, TerrainFluidVertexCoordinates.previewZ(0.0F));
    }

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
        assertEquals(8, TerrainFixedCorePolicy.overviewCellSize(80.0D));
        assertEquals(8, TerrainFixedCorePolicy.overviewCellSize(Double.NaN));
        assertTrue(TerrainFixedCorePolicy.sectionMayContainDetail(0.5D, 0.5D, 0.5D, 0, 0, 0));
        assertTrue(TerrainFixedCorePolicy.sectionMayContainDetail(16.5D, 0.5D, 0.5D, 16, 0, 0));
        assertFalse(TerrainFixedCorePolicy.sectionMayContainDetail(0.5D, 0.5D, 0.5D, 32, 0, 0));
    }

    @Test
    void replacesOnlyWhenDesiredLodChanges() {
        assertFalse(TerrainLodReevaluationPolicy.shouldReplace(TerrainLodLevel.NEAR, TerrainLodLevel.NEAR));
        assertTrue(TerrainLodReevaluationPolicy.shouldReplace(TerrainLodLevel.NEAR, TerrainLodLevel.FAR));
        assertTrue(TerrainLodReevaluationPolicy.shouldReplace(TerrainLodLevel.FAR, TerrainLodLevel.NEAR));
    }
}
