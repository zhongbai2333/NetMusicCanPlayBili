package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainCompilationAdmissionTest {
    @Test
    void tombstoneRejectsCompletedCompilationBeforeUpload() {
        var source = snapshot(new TerrainSectionKey(1, 4, 2));
        TerrainPreviewFrame frame = frame(List.of(source), Set.of(source.section()));

        assertFalse(TerrainCompilationAdmission.isCurrent(frame, source, 7L, 7L));
    }

    @Test
    void reloadStillRejectsOldSnapshotIdentityForSameSection() {
        TerrainSectionKey key = new TerrainSectionKey(1, 4, 2);
        var oldSource = snapshot(key);
        var reloadedSource = snapshot(key);
        TerrainPreviewFrame frame = frame(List.of(reloadedSource), Set.of());

        assertFalse(TerrainCompilationAdmission.isCurrent(frame, oldSource, 7L, 7L));
        assertTrue(TerrainCompilationAdmission.isCurrent(frame, reloadedSource, 7L, 7L));
        assertFalse(TerrainCompilationAdmission.isCurrent(frame, reloadedSource, 6L, 7L));
    }

    private static TerrainBlockSectionSnapshot snapshot(TerrainSectionKey key) {
        return new TerrainBlockSectionSnapshot(key, List.of(), 256L);
    }

    private static TerrainPreviewFrame frame(List<TerrainBlockSectionSnapshot> sources,
            Set<TerrainSectionKey> removed) {
        return new TerrainPreviewFrame(3L, 0, 64, 0, 0.5D, 64.5D, 0.5D,
                new TerrainBounds(-128, 0, -128, 127, 255, 127),
                List.of(), List.of(), List.of(), sources,
                sources.stream().map(source -> source.section()).collect(java.util.stream.Collectors.toSet()),
                removed, 0, sources.size());
    }
}