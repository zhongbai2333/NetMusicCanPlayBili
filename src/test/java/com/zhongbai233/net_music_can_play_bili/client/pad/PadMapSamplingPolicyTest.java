package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadMapSamplingPolicyTest {
    @Test
    void rejectsSurfaceBelowMinimumBuildHeight() {
        assertFalse(PadMapSamplingPolicy.isSurfaceHeightReady(-64, -65),
                "missing client surface data must remain UNKNOWN for retry");
    }

    @Test
    void acceptsSurfaceAtMinimumBuildHeight() {
        assertTrue(PadMapSamplingPolicy.isSurfaceHeightReady(-64, -64),
                "a valid surface at minimum build height should be sampled");
    }
}