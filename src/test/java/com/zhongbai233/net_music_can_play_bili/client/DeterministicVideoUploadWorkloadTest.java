package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeterministicVideoUploadWorkloadTest {
    @Test
    void createsExactDeterministicFrameSizes() {
        assertEquals(64 * 32 * 4, DeterministicVideoUploadWorkload.frame(
                VideoBillboardPreview.BenchUploadFormat.RGBA, 64, 32, 1).length);
        assertEquals(64 * 32 * 3 / 2, DeterministicVideoUploadWorkload.frame(
                VideoBillboardPreview.BenchUploadFormat.YUV420P, 64, 32, 1).length);
        assertEquals(64 * 32 * 3 / 2, DeterministicVideoUploadWorkload.frame(
                VideoBillboardPreview.BenchUploadFormat.NV12, 64, 32, 1).length);
        assertThrows(IllegalArgumentException.class, () -> DeterministicVideoUploadWorkload.frame(
                VideoBillboardPreview.BenchUploadFormat.NV12, 63, 32, 1));
    }
}