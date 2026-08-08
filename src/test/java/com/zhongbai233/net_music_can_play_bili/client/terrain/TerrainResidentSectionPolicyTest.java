package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainResidentSectionPolicyTest {
    @Test
    void evictsLodDowngradesAndTombstonesButKeepsExpectedNearSections() {
        TerrainSectionKey near = new TerrainSectionKey(0, 4, 0);
        TerrainSectionKey downgraded = new TerrainSectionKey(1, 4, 0);
        TerrainSectionKey unloaded = new TerrainSectionKey(2, 4, 0);
        TerrainPreviewFrame frame = new TerrainPreviewFrame(1L, 0, 64, 0, 0.5D, 64.5D, 0.5D,
            new TerrainBounds(-64, 0, -64, 63, 127, 63),
            List.of(new TerrainOverviewCell(downgraded.minBlockX(), downgraded.minBlockY(),
                downgraded.minBlockZ(), 8, TerrainCellSample.RenderCategory.MODEL)), List.of(), List.of(),
                List.of(new TerrainBlockSectionSnapshot(near, List.of(), 1L)),
                Set.of(near), Set.of(unloaded), 0, 1);

        assertEquals(Set.of(downgraded, unloaded), TerrainResidentSectionPolicy.staleSections(
                Set.of(near, downgraded, unloaded), frame));
    }
}