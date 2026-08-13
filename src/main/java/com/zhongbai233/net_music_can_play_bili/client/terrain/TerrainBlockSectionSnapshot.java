package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainNeighborhoodIndex;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainTintColors;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 固定实景核心的一段不可变可见方块快照。BlockState 是 Minecraft 的不可变状态值；
 * 快照不持有 Level、chunk、BlockEntity 或任何可变世界对象。
 */
public record TerrainBlockSectionSnapshot(TerrainSectionKey section, List<VisibleBlock> blocks,
    List<BlockState> neighborhoodStates, byte[] neighborhoodLight, long estimatedBytes) {
    public TerrainBlockSectionSnapshot(TerrainSectionKey section, List<VisibleBlock> blocks,
            long estimatedBytes) {
    this(section, blocks, List.of(), new byte[0], estimatedBytes);
    }

    public TerrainBlockSectionSnapshot(TerrainSectionKey section, List<VisibleBlock> blocks,
        List<BlockState> neighborhoodStates, long estimatedBytes) {
    this(section, blocks, neighborhoodStates, new byte[0], estimatedBytes);
    }

    public TerrainBlockSectionSnapshot {
        java.util.Objects.requireNonNull(section, "section");
        blocks = List.copyOf(java.util.Objects.requireNonNull(blocks, "blocks"));
        neighborhoodStates = List.copyOf(java.util.Objects.requireNonNull(
                neighborhoodStates, "neighborhoodStates"));
        neighborhoodLight = java.util.Objects.requireNonNull(neighborhoodLight,
            "neighborhoodLight").clone();
        if (!neighborhoodStates.isEmpty()
                && neighborhoodStates.size() != TerrainNeighborhoodIndex.CELL_COUNT) {
            throw new IllegalArgumentException("neighborhoodStates must be empty or contain exactly 20^3 states");
        }
        if (neighborhoodLight.length != 0
                && neighborhoodLight.length != TerrainNeighborhoodIndex.CELL_COUNT) {
            throw new IllegalArgumentException("neighborhoodLight must be empty or contain exactly 20^3 values");
        }
        if (estimatedBytes < 0L) {
            throw new IllegalArgumentException("estimatedBytes must be non-negative");
        }
    }

    public BlockState neighborhoodState(int localX, int localY, int localZ) {
        if (neighborhoodStates.isEmpty()
                || !TerrainNeighborhoodIndex.contains(localX, localY, localZ)) {
            return Blocks.AIR.defaultBlockState();
        }
        return neighborhoodStates.get(TerrainNeighborhoodIndex.index(localX, localY, localZ));
    }

    @Override
    public byte[] neighborhoodLight() {
        return neighborhoodLight.clone();
    }

    public byte neighborhoodLight(int localX, int localY, int localZ) {
        if (neighborhoodLight.length == 0
                || !TerrainNeighborhoodIndex.contains(localX, localY, localZ)) {
            return 0;
        }
        return neighborhoodLight[TerrainNeighborhoodIndex.index(localX, localY, localZ)];
    }

    public record VisibleBlock(int localX, int localY, int localZ, int cellSize, BlockState state,
            TerrainTintColors tintColors, List<Integer> tintLayers, byte packedLight) {
        public VisibleBlock(int localX, int localY, int localZ, BlockState state) {
            this(localX, localY, localZ, 1, state, TerrainTintColors.UNTINTED, List.of(), (byte) 0);
        }

        public VisibleBlock(int localX, int localY, int localZ, BlockState state,
                TerrainTintColors tintColors) {
            this(localX, localY, localZ, 1, state, tintColors, List.of(), (byte) 0);
        }

        public VisibleBlock {
            if (cellSize <= 0 || TerrainSectionKey.SIZE % cellSize != 0
                    || localX < 0 || localY < 0 || localZ < 0
                    || localX + cellSize > TerrainSectionKey.SIZE
                    || localY + cellSize > TerrainSectionKey.SIZE
                    || localZ + cellSize > TerrainSectionKey.SIZE) {
                throw new IllegalArgumentException("visible terrain cell must fit inside its section");
            }
            java.util.Objects.requireNonNull(state, "state");
            java.util.Objects.requireNonNull(tintColors, "tintColors");
            tintLayers = List.copyOf(java.util.Objects.requireNonNull(tintLayers, "tintLayers"));
        }
    }
}
