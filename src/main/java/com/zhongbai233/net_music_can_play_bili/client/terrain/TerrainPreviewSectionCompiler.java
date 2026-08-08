package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainFluidVertexCoordinates;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.core.BlockPos;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 把不可变方块快照编译成原版 terrain 顶点格式，并按 {@link ChunkSectionLayer} 分层。
 * 使用不可变的状态、群系 tint 与真实光照快照，并按原版规则执行面剔除和 AO；模型、贴图和材质层来自当前资源包。
 */
public final class TerrainPreviewSectionCompiler {
    private static final org.slf4j.Logger LOGGER =
        LoggerFactory.getLogger(TerrainPreviewSectionCompiler.class);
    private static final int INITIAL_LAYER_BUFFER_BYTES = 256 * 1024;
    private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();

    private TerrainPreviewSectionCompiler() {
    }

    public static CompiledCpuSection compile(TerrainPreviewFrame frame,
            TerrainBlockSectionSnapshot snapshot, BlockStateModelSet models,
            FluidStateModelSet fluidModels, BlockColors blockColors) {
        java.util.Objects.requireNonNull(models, "models");
        java.util.Objects.requireNonNull(fluidModels, "fluidModels");
        java.util.Objects.requireNonNull(blockColors, "blockColors");
        ModelBlockRenderer renderer = new ModelBlockRenderer(true, true, blockColors);
        FluidRenderer fluidRenderer = new FluidRenderer(fluidModels);
        TerrainPreviewBlockAndTintGetter snapshotView = new TerrainPreviewBlockAndTintGetter(snapshot);
        ByteBufferBuilder[] storageByLayer = new ByteBufferBuilder[LAYERS.length];
        BufferBuilder[] buildersByLayer = new BufferBuilder[LAYERS.length];
        Map<ChunkSectionLayer, MeshData> meshes = new EnumMap<>(ChunkSectionLayer.class);
        BlockQuadOutput output = (x, y, z, quad, instance) -> builderFor(
            quad.materialInfo().layer(), storageByLayer, buildersByLayer)
            .putBlockBakedQuad(x, y, z, quad, instance);
        Map<net.minecraft.world.level.block.state.BlockState, BlockStateModel> modelByState =
            new HashMap<>();

        BlockModelLighter.enableCaching();
        try {
            BlockPos.MutableBlockPos worldPos = new BlockPos.MutableBlockPos();
            for (TerrainBlockSectionSnapshot.VisibleBlock block : snapshot.blocks()) {
                int worldX = snapshot.section().minBlockX() + block.localX();
                int worldY = snapshot.section().minBlockY() + block.localY();
                int worldZ = snapshot.section().minBlockZ() + block.localZ();
                if (worldX == frame.originX() && worldY == frame.originY() && worldZ == frame.originZ()) {
                    continue;
                }
                worldPos.set(worldX, worldY, worldZ);
                try {
                    var fluidState = block.state().getFluidState();
                    if (!fluidState.isEmpty()) {
                        fluidRenderer.tesselate(snapshotView, worldPos,
                            layer -> sectionLocalFluidOutput(
                                builderFor(layer, storageByLayer, buildersByLayer)),
                            block.state(), fluidState);
                    }
                    if (block.state().getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL) {
                        BlockStateModel model = modelByState.computeIfAbsent(block.state(), models::get);
                        renderer.tesselateBlock(output,
                            block.localX() - 0.5F, block.localY(), block.localZ() - 0.5F,
                            snapshotView, worldPos, block.state(), model,
                            block.state().getSeed(worldPos));
                    }
                } catch (Throwable incompatibleModBlock) {
                    if (incompatibleModBlock instanceof VirtualMachineError fatal) {
                        throw fatal;
                    }
                    if (incompatibleModBlock instanceof Error fatal) {
                        throw fatal;
                    }
                    LOGGER.warn("Skipping incompatible terrain preview block at ({}, {}, {}) in section {}",
                        worldX, worldY, worldZ, snapshot.section(), incompatibleModBlock);
                }
            }
            Map<ChunkSectionLayer, ByteBufferBuilder> storage = new EnumMap<>(ChunkSectionLayer.class);
            for (int i = 0; i < LAYERS.length; i++) {
                BufferBuilder builder = buildersByLayer[i];
                if (builder == null) {
                    continue;
                }
                MeshData mesh = builder.build();
                if (mesh != null) {
                    meshes.put(LAYERS[i], mesh);
                }
                storage.put(LAYERS[i], storageByLayer[i]);
            }
            return new CompiledCpuSection(snapshot, meshes, storage);
        } catch (Throwable failure) {
            meshes.values().forEach(mesh -> mesh.close());
            closeStorage(storageByLayer);
            throw failure;
        } finally {
            BlockModelLighter.clearCache();
        }
    }

    private static BufferBuilder builderFor(ChunkSectionLayer layer,
            ByteBufferBuilder[] storage, BufferBuilder[] builders) {
        int index = layer.ordinal();
        BufferBuilder builder = builders[index];
        if (builder == null) {
            ByteBufferBuilder bytes = new ByteBufferBuilder(INITIAL_LAYER_BUFFER_BYTES);
            storage[index] = bytes;
            builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, layer.vertexFormat());
            builders[index] = builder;
        }
        return builder;
    }

    static VertexConsumer sectionLocalFluidOutput(VertexConsumer delegate) {
        return new TranslatedVertexConsumer(delegate,
            TerrainFluidVertexCoordinates.offsetX(),
            TerrainFluidVertexCoordinates.offsetY(),
            TerrainFluidVertexCoordinates.offsetZ());
    }

    private static final class TranslatedVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float offsetX;
        private final float offsetY;
        private final float offsetZ;

        private TranslatedVertexConsumer(VertexConsumer delegate,
                float offsetX, float offsetY, float offsetZ) {
            this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x + offsetX, y + offsetY, z + offsetZ);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            delegate.setColor(color);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            delegate.setLineWidth(width);
            return this;
        }
    }

    private static void closeStorage(ByteBufferBuilder[] storage) {
        for (ByteBufferBuilder bytes : storage) {
            if (bytes != null) {
                bytes.close();
            }
        }
    }

    public record CompiledCpuSection(TerrainBlockSectionSnapshot source,
            Map<ChunkSectionLayer, MeshData> layers,
            Map<ChunkSectionLayer, ByteBufferBuilder> storage) implements AutoCloseable {
        public CompiledCpuSection {
            java.util.Objects.requireNonNull(source, "source");
            layers = new EnumMap<>(java.util.Objects.requireNonNull(layers, "layers"));
            storage = new EnumMap<>(java.util.Objects.requireNonNull(storage, "storage"));
        }

        @Override
        public void close() {
            layers.values().forEach(mesh -> mesh.close());
            layers.clear();
            storage.values().forEach(bytes -> bytes.close());
            storage.clear();
        }
    }
}