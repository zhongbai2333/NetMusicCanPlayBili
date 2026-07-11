package com.zhongbai233.net_music_can_play_bili.client.pad;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Detects whether the Pad map should sample an outdoor map or an indoor floor
 * layer.
 */
final class PadMapViewProfileDetector {
    private final int ceilingScanBlocks;
    private final int ceilingMinHits;
    private final int artificialMinHits;

    PadMapViewProfileDetector(int ceilingScanBlocks, int ceilingMinHits, int artificialMinHits) {
        this.ceilingScanBlocks = ceilingScanBlocks;
        this.ceilingMinHits = ceilingMinHits;
        this.artificialMinHits = artificialMinHits;
    }

    PadMapViewProfile detect(Level level, BlockPos playerPos) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int ceiling = 0;
        int artificialCeiling = 0;
        int artificial = 0;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = playerPos.getX() + dx;
                int z = playerPos.getZ() + dz;
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (surfaceY >= playerPos.getY() + 2 && surfaceY <= playerPos.getY() + ceilingScanBlocks) {
                    ceiling++;
                    mutable.set(x, surfaceY, z);
                    if (isArtificialProfileBlock(level, mutable, level.getBlockState(mutable))) {
                        artificialCeiling++;
                    }
                }
                for (int dy = -1; dy <= 5; dy++) {
                    mutable.set(x, playerPos.getY() + dy, z);
                    BlockState state = level.getBlockState(mutable);
                    if (isArtificialProfileBlock(level, mutable, state)) {
                        artificial++;
                        break;
                    }
                }
            }
        }
        return isIndoorEvidence(ceiling, artificialCeiling, artificial, ceilingMinHits, artificialMinHits)
                ? PadMapViewProfile.INDOOR
                : PadMapViewProfile.OUTDOOR;
    }

    static boolean isIndoorEvidence(int ceilingHits, int artificialCeilingHits, int nearbyArtificialHits,
            int ceilingMinHits, int artificialMinHits) {
        return ceilingHits >= ceilingMinHits
                && (artificialCeilingHits >= ceilingMinHits || nearbyArtificialHits >= artificialMinHits);
    }

    int normalizeIndoorFloorY(Level level, BlockPos playerPos) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy >= -2; dy--) {
            int y = playerPos.getY() + dy;
            if (y - 1 < level.getMinY()) {
                continue;
            }
            mutable.set(playerPos.getX(), y, playerPos.getZ());
            BlockState feet = level.getBlockState(mutable);
            boolean feetOpen = feet.isAir() || feet.getCollisionShape(level, mutable).isEmpty();
            mutable.set(playerPos.getX(), y + 1, playerPos.getZ());
            BlockState head = level.getBlockState(mutable);
            boolean headOpen = head.isAir() || head.getCollisionShape(level, mutable).isEmpty();
            mutable.set(playerPos.getX(), y - 1, playerPos.getZ());
            BlockState below = level.getBlockState(mutable);
            if (feetOpen && headOpen && !below.getCollisionShape(level, mutable).isEmpty()) {
                return y;
            }
        }
        return playerPos.getY();
    }

    int outdoorLayerY() {
        return PadMapViewProfileStabilizer.OUTDOOR_LAYER_Y;
    }

    private static boolean isArtificialProfileBlock(Level level, BlockPos.MutableBlockPos mutable,
            BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() || state.getCollisionShape(level, mutable).isEmpty()) {
            return false;
        }
        return !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS) && !PadMapSampler.isNaturalTerrain(state);
    }
}
