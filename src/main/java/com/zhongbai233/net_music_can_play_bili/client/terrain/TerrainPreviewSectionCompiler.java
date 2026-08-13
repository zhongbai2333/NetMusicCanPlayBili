package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.platform.Transparency;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainFluidVertexCoordinates;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainPackedLight;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
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
        ModelBlockRenderer renderer = new ModelBlockRenderer(true, true,
                new FrozenTerrainBlockColors(snapshot));
        FluidRenderer fluidRenderer = new FluidRenderer(fluidModels);
        TerrainPreviewBlockAndTintGetter snapshotView = new TerrainPreviewBlockAndTintGetter(snapshot);
        ByteBufferBuilder[] storageByLayer = new ByteBufferBuilder[LAYERS.length];
        BufferBuilder[] buildersByLayer = new BufferBuilder[LAYERS.length];
        Map<ChunkSectionLayer, MeshData> meshes = new EnumMap<>(ChunkSectionLayer.class);
        Map<ChunkSectionLayer, MeshData.SortState> sortStates = new EnumMap<>(ChunkSectionLayer.class);
        java.util.List<ByteBufferBuilder> auxiliaryStorage = new java.util.ArrayList<>();
        BlockQuadOutput output = (x, y, z, quad, instance) -> builderFor(
            quad.materialInfo().layer(), storageByLayer, buildersByLayer)
            .putBlockBakedQuad(x, y, z, quad, instance);
        Map<net.minecraft.world.level.block.state.BlockState, BlockStateModel> modelByState =
            new HashMap<>();

        BlockModelLighter.enableCaching();
        try {
            BlockPos.MutableBlockPos worldPos = new BlockPos.MutableBlockPos();
            boolean[] occupied = occupiedVoxels(snapshot);
            for (TerrainBlockSectionSnapshot.VisibleBlock block : snapshot.blocks()) {
                int worldX = snapshot.section().minBlockX() + block.localX();
                int worldY = snapshot.section().minBlockY() + block.localY();
                int worldZ = snapshot.section().minBlockZ() + block.localZ();
                if (worldX == frame.originX() && worldY == frame.originY() && worldZ == frame.originZ()) {
                    continue;
                }
                worldPos.set(worldX, worldY, worldZ);
                try {
                    if (block.cellSize() > 1) {
                        tesselateMaterialCell(block, snapshotView, worldPos, models, fluidModels,
                                occupied, storageByLayer, buildersByLayer);
                        continue;
                    }
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
                    if (LAYERS[i] == ChunkSectionLayer.TRANSLUCENT) {
                        int initialIndexBytes = Math.max(256,
                                mesh.drawState().indexCount() * mesh.drawState().indexType().bytes);
                        ByteBufferBuilder indexStorage = new ByteBufferBuilder(initialIndexBytes);
                        MeshData.SortState sortState = mesh.sortQuads(indexStorage,
                                VertexSorting.DISTANCE_TO_ORIGIN);
                        if (sortState != null) {
                            sortStates.put(LAYERS[i], sortState);
                            auxiliaryStorage.add(indexStorage);
                        } else {
                            indexStorage.close();
                        }
                    }
                    meshes.put(LAYERS[i], mesh);
                }
                storage.put(LAYERS[i], storageByLayer[i]);
            }
            return new CompiledCpuSection(snapshot, meshes, storage, sortStates, auxiliaryStorage);
        } catch (Throwable failure) {
            meshes.values().forEach(mesh -> mesh.close());
            closeStorage(storageByLayer);
            auxiliaryStorage.forEach(storageBuffer -> storageBuffer.close());
            throw failure;
        } finally {
            BlockModelLighter.clearCache();
        }
    }

    private static boolean[] occupiedVoxels(TerrainBlockSectionSnapshot snapshot) {
        boolean[] occupied = new boolean[16 * 16 * 16];
        for (TerrainBlockSectionSnapshot.VisibleBlock block : snapshot.blocks()) {
            for (int y = block.localY(); y < block.localY() + block.cellSize(); y++) {
                for (int z = block.localZ(); z < block.localZ() + block.cellSize(); z++) {
                    for (int x = block.localX(); x < block.localX() + block.cellSize(); x++) {
                        occupied[(y * 16 + z) * 16 + x] = true;
                    }
                }
            }
        }
        return occupied;
    }

    private static void tesselateMaterialCell(TerrainBlockSectionSnapshot.VisibleBlock block,
            TerrainPreviewBlockAndTintGetter snapshotView, BlockPos worldPos,
            BlockStateModelSet models, FluidStateModelSet fluidModels, boolean[] occupied,
            ByteBufferBuilder[] storageByLayer, BufferBuilder[] buildersByLayer) {
        TextureAtlasSprite sprite;
        ChunkSectionLayer layer;
        var fluidState = block.state().getFluidState();
        if (!fluidState.isEmpty()) {
            var fluidModel = fluidModels.get(fluidState);
            sprite = fluidModel.stillMaterial().sprite();
            layer = fluidModel.layer();
        } else {
            BlockStateModel model = models.get(block.state());
            var material = model.particleMaterial(snapshotView, worldPos, block.state());
            sprite = material.sprite();
            Transparency transparency = material.forceTranslucent()
                    ? Transparency.TRANSLUCENT : sprite.transparency();
            layer = ChunkSectionLayer.byTransparency(transparency);
        }
        BufferBuilder output = builderFor(layer, storageByLayer, buildersByLayer);
        int tint = block.tintLayers().isEmpty() ? -1 : block.tintLayers().getFirst();
        if (tint != -1 && (tint & 0xFF000000) == 0) {
            tint |= 0xFF000000;
        }
        int light = LightCoordsUtil.pack(TerrainPackedLight.block(block.packedLight()),
                TerrainPackedLight.sky(block.packedLight()));
        for (Direction direction : Direction.values()) {
            if (faceExposed(block, direction, occupied)) {
                emitCellFace(output, block, direction, sprite, shade(tint, direction), light);
            }
        }
    }

    private static boolean faceExposed(TerrainBlockSectionSnapshot.VisibleBlock block,
            Direction direction, boolean[] occupied) {
        int x = block.localX() + direction.getStepX() * block.cellSize();
        int y = block.localY() + direction.getStepY() * block.cellSize();
        int z = block.localZ() + direction.getStepZ() * block.cellSize();
        if (x < 0 || x >= 16 || y < 0 || y >= 16 || z < 0 || z >= 16) {
            return true;
        }
        return !occupied[(y * 16 + z) * 16 + x];
    }

    private static int shade(int color, Direction direction) {
        float factor = switch (direction) {
            case DOWN -> 0.5F;
            case UP -> 1.0F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
        };
        return ARGB.scaleRGB(color, factor);
    }

    private static void emitCellFace(BufferBuilder output,
            TerrainBlockSectionSnapshot.VisibleBlock block, Direction direction,
            TextureAtlasSprite sprite, int color, int light) {
        float x0 = block.localX() - 0.5F;
        float y0 = block.localY();
        float z0 = block.localZ() - 0.5F;
        float x1 = x0 + block.cellSize();
        float y1 = y0 + block.cellSize();
        float z1 = z0 + block.cellSize();
        float[][] vertices = switch (direction) {
            case DOWN -> new float[][] {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
            case UP -> new float[][] {{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
            case NORTH -> new float[][] {{x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}};
            case SOUTH -> new float[][] {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            case WEST -> new float[][] {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
            case EAST -> new float[][] {{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}};
        };
        float[] u = {sprite.getU0(), sprite.getU1(), sprite.getU1(), sprite.getU0()};
        float[] v = {sprite.getV1(), sprite.getV1(), sprite.getV0(), sprite.getV0()};
        var normal = direction.getUnitVec3f();
        for (int index = 0; index < 4; index++) {
            output.addVertex(vertices[index][0], vertices[index][1], vertices[index][2],
                    color, u[index], v[index], 0, light, normal.x(), normal.y(), normal.z());
        }
    }

    /** Supplies frozen per-position tint values without invoking mod or level code on the compiler thread. */
    private static final class FrozenTerrainBlockColors extends BlockColors {
        private final Map<net.minecraft.world.level.block.state.BlockState, java.util.List<BlockTintSource>> sources =
                new HashMap<>();

        private FrozenTerrainBlockColors(TerrainBlockSectionSnapshot snapshot) {
            Map<net.minecraft.world.level.block.state.BlockState, Integer> layers = new HashMap<>();
            for (TerrainBlockSectionSnapshot.VisibleBlock block : snapshot.blocks()) {
                int layerCount = block.tintLayers().size();
                Integer current = layers.get(block.state());
                if (current == null || layerCount > current.intValue()) {
                    layers.put(block.state(), layerCount);
                }
            }
            layers.forEach((state, count) -> {
                java.util.List<BlockTintSource> values = new java.util.ArrayList<>(count);
                for (int layer = 0; layer < count; layer++) {
                    final int tintLayer = layer;
                    values.add(new BlockTintSource() {
                        @Override
                        public int color(net.minecraft.world.level.block.state.BlockState ignored) {
                            return -1;
                        }

                        @Override
                        public int colorInWorld(net.minecraft.world.level.block.state.BlockState ignored,
                                net.minecraft.client.renderer.block.BlockAndTintGetter level, BlockPos pos) {
                            return level instanceof TerrainPreviewBlockAndTintGetter frozen
                                    ? frozen.precomputedTint(pos, tintLayer) : -1;
                        }
                    });
                }
                sources.put(state, java.util.List.copyOf(values));
            });
        }

        @Override
        public java.util.List<BlockTintSource> getTintSources(
                net.minecraft.world.level.block.state.BlockState state) {
            return sources.getOrDefault(state, java.util.List.of());
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
            Map<ChunkSectionLayer, ByteBufferBuilder> storage,
            Map<ChunkSectionLayer, MeshData.SortState> sortStates,
            java.util.List<ByteBufferBuilder> auxiliaryStorage) implements AutoCloseable {
        public CompiledCpuSection {
            java.util.Objects.requireNonNull(source, "source");
            layers = new EnumMap<>(java.util.Objects.requireNonNull(layers, "layers"));
            storage = new EnumMap<>(java.util.Objects.requireNonNull(storage, "storage"));
            sortStates = new EnumMap<>(java.util.Objects.requireNonNull(sortStates, "sortStates"));
            auxiliaryStorage = new java.util.ArrayList<>(java.util.Objects.requireNonNull(
                    auxiliaryStorage, "auxiliaryStorage"));
        }

        @Override
        public void close() {
            layers.values().forEach(mesh -> mesh.close());
            layers.clear();
            storage.values().forEach(bytes -> bytes.close());
            storage.clear();
            sortStates.clear();
            auxiliaryStorage.forEach(storageBuffer -> storageBuffer.close());
            auxiliaryStorage.clear();
        }
    }
}
