package com.zhongbai233.net_music_can_play_bili.client.pad;

/** Lightweight regression tests for Pad map sampling readiness decisions. */
public final class PadMapSamplerLogicSelfTest {
    private PadMapSamplerLogicSelfTest() {
    }

    public static void main(String[] args) {
        rejectsSurfaceBelowMinimumBuildHeight();
        acceptsSurfaceAtMinimumBuildHeight();
        System.out.println("PadMapSamplerLogicSelfTest passed");
    }

    private static void rejectsSurfaceBelowMinimumBuildHeight() {
        if (PadMapSampler.isSurfaceHeightReady(-64, -65)) {
            throw new AssertionError("missing client surface data must remain UNKNOWN for retry");
        }
    }

    private static void acceptsSurfaceAtMinimumBuildHeight() {
        if (!PadMapSampler.isSurfaceHeightReady(-64, -64)) {
            throw new AssertionError("a valid surface at minimum build height should be sampled");
        }
    }
}