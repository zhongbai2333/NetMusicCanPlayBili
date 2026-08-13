package com.zhongbai233.net_music_can_play_bili.client.terrain;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.BlockPos;

/** One client-thread-extracted block-entity render state consumed without touching the live level in PIP. */
public record TerrainBlockEntityPreview(BlockPos worldPos, BlockEntityRenderState renderState) {
    public TerrainBlockEntityPreview {
        worldPos = java.util.Objects.requireNonNull(worldPos, "worldPos").immutable();
        java.util.Objects.requireNonNull(renderState, "renderState");
    }
}
