package com.zhongbai233.net_music_can_play_bili.terrain.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerrainPackedLightTest {
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
}