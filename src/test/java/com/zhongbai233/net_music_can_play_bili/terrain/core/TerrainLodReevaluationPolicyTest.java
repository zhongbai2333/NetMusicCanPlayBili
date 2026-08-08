package com.zhongbai233.net_music_can_play_bili.terrain.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainLodReevaluationPolicyTest {
    @Test
    void replacesOnlyWhenDesiredLodChanges() {
        assertFalse(TerrainLodReevaluationPolicy.shouldReplace(TerrainLodLevel.NEAR, TerrainLodLevel.NEAR));
        assertTrue(TerrainLodReevaluationPolicy.shouldReplace(TerrainLodLevel.NEAR, TerrainLodLevel.FAR));
        assertTrue(TerrainLodReevaluationPolicy.shouldReplace(TerrainLodLevel.FAR, TerrainLodLevel.NEAR));
    }
}