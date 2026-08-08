package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainPreviewChunkInvalidationTest {
    @Test
    void enumeratesOnlyVerticalSectionsInsideBoundsAndWorldHeight() {
        Set<TerrainSectionKey> keys = TerrainPreviewManager.sectionKeysForChunk(-1, 2,
                new TerrainBounds(-16, -10, 32, -1, 40, 47), -64, 320);

        assertEquals(Set.of(new TerrainSectionKey(-1, -1, 2), new TerrainSectionKey(-1, 0, 2),
                new TerrainSectionKey(-1, 1, 2), new TerrainSectionKey(-1, 2, 2)), keys);
        assertTrue(TerrainPreviewManager.sectionKeysForChunk(4, 4,
                new TerrainBounds(-16, -10, 32, -1, 40, 47), -64, 320).isEmpty());
    }
}