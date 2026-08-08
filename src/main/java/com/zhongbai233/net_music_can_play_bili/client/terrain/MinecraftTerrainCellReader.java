package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionCaptureJob;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Minecraft 世界到纯地形快照的最小适配器。只能在创建它的客户端主线程调用，且永不强制加载区块。
 */
public final class MinecraftTerrainCellReader implements TerrainSectionCaptureJob.TerrainCellReader {
    private final ClientLevel level;
    private final Thread ownerThread;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public MinecraftTerrainCellReader(ClientLevel level) {
        this.level = java.util.Objects.requireNonNull(level, "level");
        this.ownerThread = Thread.currentThread();
    }

    @Override
    public TerrainCellSample read(int blockX, int blockY, int blockZ) {
        requireOwnerThread();
        if (blockY < level.getMinY() || blockY >= level.getMaxY()
                || !level.hasChunk(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16))) {
            return TerrainCellSample.unknown();
        }
        try {
            cursor.set(blockX, blockY, blockZ);
            BlockState state = level.getBlockState(cursor);
            FluidState fluid = state.getFluidState();
            String fluidId = fluid.isEmpty() ? "" : BuiltInRegistries.FLUID.getKey(fluid.getType()).toString();
            if (state.isAir() && fluid.isEmpty()) {
                return TerrainCellSample.air();
            }
            RenderShape renderShape = state.getRenderShape();
            TerrainCellSample.RenderCategory category = switch (renderShape) {
                case MODEL -> TerrainCellSample.RenderCategory.MODEL;
                case INVISIBLE -> TerrainCellSample.RenderCategory.INVISIBLE;
            };
            boolean hasBlockEntity = state.hasBlockEntity();
            return new TerrainCellSample(TerrainCellSample.Availability.LOADED, category,
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), fluidId, hasBlockEntity,
                    hasBlockEntity || category == TerrainCellSample.RenderCategory.ENTITY_ANIMATED);
        } catch (Throwable incompatibleModBlock) {
            if (incompatibleModBlock instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            // 第三方方块 getter/render-shape 回调失败时只降级当前单元，不能中止整个 section 捕获。
            return TerrainCellSample.unknown();
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Minecraft terrain cells must be sampled on the owning client thread");
        }
    }
}