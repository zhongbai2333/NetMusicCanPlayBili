package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import net.minecraft.world.level.block.state.BlockState;

/** Bench-only access to the package-private production occlusion policy. */
public final class VideoScreenOcclusionBenchProbe {
    private VideoScreenOcclusionBenchProbe() {
    }

    public static boolean blocksView(BlockState state) {
        return VideoScreenOcclusion.blocksView(state);
    }
}
