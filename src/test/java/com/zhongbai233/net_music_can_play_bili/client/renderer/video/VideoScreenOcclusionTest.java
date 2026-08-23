package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VideoScreenOcclusionTest {
    @Test
    void opaqueFullCubeBlocksScreen() {
        assertTrue(VideoScreenOcclusion.blocksView(true, true, true));
    }

    @Test
    void transparentFullCubeDoesNotBlockScreen() {
        assertFalse(VideoScreenOcclusion.blocksView(false, false, true));
    }

    @Test
    void semiTransparentMaterialDoesNotBlockScreen() {
        assertFalse(VideoScreenOcclusion.blocksView(true, false, true));
    }

    @Test
    void incompleteShapeDoesNotBlockScreen() {
        assertFalse(VideoScreenOcclusion.blocksView(true, true, false));
    }
}
