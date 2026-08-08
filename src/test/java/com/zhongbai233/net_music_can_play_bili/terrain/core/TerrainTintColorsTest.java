package com.zhongbai233.net_music_can_play_bili.terrain.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainTintColorsTest {
    @Test
    void preservesEachCapturedBiomeTint() {
        TerrainTintColors colors = new TerrainTintColors(
                0xFF55AA33, 0xFF448822, 0xFF998844, 0xFF3366CC);

        assertEquals(0xFF55AA33, colors.color(TerrainTintColors.TintType.GRASS));
        assertEquals(0xFF448822, colors.color(TerrainTintColors.TintType.FOLIAGE));
        assertEquals(0xFF998844, colors.color(TerrainTintColors.TintType.DRY_FOLIAGE));
        assertEquals(0xFF3366CC, colors.color(TerrainTintColors.TintType.WATER));
    }
}