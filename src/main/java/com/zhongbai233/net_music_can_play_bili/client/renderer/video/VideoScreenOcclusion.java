package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Line-of-sight policy for video screens. */
final class VideoScreenOcclusion {
    private VideoScreenOcclusion() {
    }

    static boolean isOccluded(BlockGetter level, Vec3 cameraPos, Vec3 target, BlockPos ignoredSourcePos) {
        if (level == null || cameraPos == null || target == null) {
            return false;
        }
        return BlockGetter.traverseBlocks(cameraPos, target, level, (world, pos) -> {
            if (pos.equals(ignoredSourcePos)) {
                return null;
            }
            return blocksView(world.getBlockState(pos)) ? Boolean.TRUE : null;
        }, world -> Boolean.FALSE);
    }

    static boolean blocksView(BlockState state) {
        if (state == null) {
            return false;
        }
        return blocksView(state.canOcclude(), state.isSolidRender(),
                Block.isShapeFullBlock(state.getOcclusionShape()));
    }

    static boolean blocksView(boolean canOcclude, boolean solidRender, boolean fullOcclusionShape) {
        return canOcclude && solidRender && fullOcclusionShape;
    }
}
