package com.zhongbai233.net_music_can_play_bili.client.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerrainHardRangeBoundsTest {
    @Test
    void buildsInclusiveBoundsAroundConsoleCenterAndClipsWorldHeight() {
        var bounds = TerrainHardRangeBounds.around(-10, 300, 20,
                16.0D, 64.0D, 0.5D, -64, 320);

        assertEquals(-26, bounds.minX());
        assertEquals(6, bounds.maxX());
        assertEquals(236, bounds.minY());
        assertEquals(319, bounds.maxY());
        assertEquals(20, bounds.minZ());
        assertEquals(21, bounds.maxZ());
    }

    @Test
    void rejectsInvalidRanges() {
        assertThrows(IllegalArgumentException.class, () -> TerrainHardRangeBounds.around(
            0, 0, 0, Double.NaN, 1.0D, 1.0D, -64, 320));
        assertThrows(IllegalArgumentException.class, () -> TerrainHardRangeBounds.around(
            0, 0, 0, -1.0D, 1.0D, 1.0D, -64, 320));
    }
}