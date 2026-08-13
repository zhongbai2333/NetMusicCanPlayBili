package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainPreviewRenderDiagnosticsTest {
    @Test
    void snapshotDeltasRemainScalarAndMonotonic() {
        TerrainPreviewRenderDiagnostics.Snapshot baseline = TerrainPreviewRenderDiagnostics.snapshot();

        TerrainPreviewRenderDiagnostics.recordSectionUpload(true, true);
        TerrainPreviewRenderDiagnostics.recordTranslucentResort();
        TerrainPreviewRenderDiagnostics.recordBlockEntitySubmission();
        TerrainPreviewRenderDiagnostics.recordFailure();

        assertEquals(new TerrainPreviewRenderDiagnostics.Snapshot(1L, 1L, 1L, 1L, 1L),
                TerrainPreviewRenderDiagnostics.snapshot().deltaFrom(baseline));
        assertEquals(new TerrainPreviewRenderDiagnostics.Snapshot(0L, 0L, 0L, 0L, 0L),
                baseline.deltaFrom(TerrainPreviewRenderDiagnostics.snapshot()));
    }
}
