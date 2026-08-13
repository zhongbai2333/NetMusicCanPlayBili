package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.GraphicsWorkarounds;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainBlockSectionSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainCompilationAdmission;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewFrame;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewSectionCompiler;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainResidentSectionPolicy;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainTranslucentSortPolicy;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.lang.ref.WeakReference;

/** 渲染线程所有的 section 持久 GPU 网格缓存。 */
final class TerrainPreviewGpuCache implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TerrainPreviewGpuCache.class);
        private static final Identifier BLOCK_ATLAS_LOCATION =
            Identifier.withDefaultNamespace("textures/atlas/blocks.png");
    private static final List<String> CHUNK_SECTION_UNIFORM = List.of("ChunkSection");
    private static final long COVERAGE_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private final Map<TerrainSectionKey, GpuSection> sections = new HashMap<>();
    private final Map<TerrainSectionKey, TerrainBlockSectionSnapshot> failedSources = new HashMap<>();
    private final ConcurrentLinkedQueue<CompilationOutcome> completedCompilations =
        new ConcurrentLinkedQueue<>();
    private final ExecutorService compilationExecutor = Executors.newSingleThreadExecutor(
        terrainCompilerThreadFactory());
    private CompilationRequest activeCompilation;
    private long compilationEpoch;
    private long generation;
    private BlockStateModelSet modelSet;
    private TerrainBounds synchronizedBounds;
    private SharedTerrainBuffers sharedBuffers;
    private long lastCoverageLogNanos;
    private long lastCoverageGeneration = Long.MIN_VALUE;
    private TerrainPreviewFrame exhaustedCompilationFrame;
    private Matrix4f exhaustedCompilationViewProjection;
    private long residentRevision;
    private RenderPlan cachedRenderPlan;
    private UniformPlan cachedUniformPlan;
    private long renderPlanHits;
    private long renderPlanBuilds;
    private long sessionReleases;
    private volatile boolean closed;
    private boolean disabledForSession;
    private boolean failureLogged;

    void updateAndRender(TerrainPreviewFrame frame, Matrix4fc modelView,
            HolographicPreviewPipRenderState state) {
        if (disabledForSession) {
            return;
        }
        try {
            updateAndRenderInternal(frame, modelView, state);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            disableForSession("terrain preview GPU/render failure", failure);
        }
    }

    private void updateAndRenderInternal(TerrainPreviewFrame frame, Matrix4fc modelView,
            HolographicPreviewPipRenderState state) {
        BlockStateModelSet currentModels = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (frame.generation() != generation || currentModels != modelSet) {
            clear();
            generation = frame.generation();
            modelSet = currentModels;
        }
        if (sharedBuffers == null) {
            sharedBuffers = new SharedTerrainBuffers();
        }
        removeTombstonedSections(frame);
        removeNonResidentSections(frame);
        synchronizeCoverage(frame);
        Matrix4f viewProjection = state.cameraFrame() != null
            ? state.cameraFrame().matrices().viewProjection() : new Matrix4f();
        updateCompilation(frame, modelView, viewProjection);
        renderLayers(frame, modelView, viewProjection);
    }

    private void synchronizeCoverage(TerrainPreviewFrame frame) {
        if (frame.bounds().equals(synchronizedBounds)) {
            return;
        }
        synchronizedBounds = frame.bounds();
        boolean removed = sections.entrySet().removeIf(entry -> {
            if (frame.bounds().intersects(entry.getKey())) {
                return false;
            }
            entry.getValue().close();
            failedSources.remove(entry.getKey());
            return true;
        });
        if (removed) {
            residentRevision++;
            cachedRenderPlan = null;
            exhaustedCompilationFrame = null;
            exhaustedCompilationViewProjection = null;
        }
    }

    private void removeTombstonedSections(TerrainPreviewFrame frame) {
        boolean removed = false;
        for (TerrainSectionKey key : frame.removedSections()) {
            GpuSection section = sections.remove(key);
            if (section != null) {
                section.close();
                removed = true;
            }
            failedSources.remove(key);
        }
        if (removed) {
            residentRevision++;
            cachedRenderPlan = null;
            cachedUniformPlan = null;
            exhaustedCompilationFrame = null;
            exhaustedCompilationViewProjection = null;
        }
    }

    private void removeNonResidentSections(TerrainPreviewFrame frame) {
        boolean removed = false;
        for (TerrainSectionKey key : TerrainResidentSectionPolicy.staleSections(sections.keySet(), frame)) {
            GpuSection section = sections.remove(key);
            if (section != null) {
                section.close();
                removed = true;
            }
            failedSources.remove(key);
        }
        if (removed) {
            residentRevision++;
            cachedRenderPlan = null;
            cachedUniformPlan = null;
            exhaustedCompilationFrame = null;
            exhaustedCompilationViewProjection = null;
        }
    }

    private void updateCompilation(TerrainPreviewFrame frame, Matrix4fc modelView,
            Matrix4fc viewProjection) {
        consumeCompletedCompilation(frame);
        if (activeCompilation != null || closed) {
            return;
        }
        if (frame == exhaustedCompilationFrame
            && exhaustedCompilationViewProjection != null
            && exhaustedCompilationViewProjection.equals(viewProjection)) {
            return;
        }
        // 已驻留 section 的 dirty 快照优先，避免方块更新长期显示旧网格。
        for (TerrainBlockSectionSnapshot snapshot : frame.fullDetailSections()) {
            GpuSection current = sections.get(snapshot.section());
            if (current == null || current.source.get() == snapshot) {
                continue;
            }
            if (failedSources.get(snapshot.section()) == snapshot) {
                continue;
            }
            exhaustedCompilationFrame = null;
            exhaustedCompilationViewProjection = null;
            submitCompilation(frame, snapshot);
            return;
        }

        TerrainBlockSectionSnapshot nearestMissing = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        TerrainBlockSectionSnapshot nearestMissingOutsideFrustum = null;
        double nearestOutsideDistance = Double.POSITIVE_INFINITY;
        for (TerrainBlockSectionSnapshot snapshot : frame.fullDetailSections()) {
            if (sections.containsKey(snapshot.section())
                    || failedSources.get(snapshot.section()) == snapshot) {
                continue;
            }
            double distance = viewDistanceSquared(modelView, frame, snapshot.section());
            if (intersectsFrustum(frame, viewProjection, snapshot.section())) {
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestMissing = snapshot;
                }
            } else if (distance < nearestOutsideDistance) {
                nearestOutsideDistance = distance;
                nearestMissingOutsideFrustum = snapshot;
            }
        }
        if (nearestMissing == null) {
            nearestMissing = nearestMissingOutsideFrustum;
        }
        if (nearestMissing != null) {
            exhaustedCompilationFrame = null;
            exhaustedCompilationViewProjection = null;
            submitCompilation(frame, nearestMissing);
        } else {
            exhaustedCompilationFrame = frame;
            exhaustedCompilationViewProjection = new Matrix4f(viewProjection);
        }
    }

    private static boolean intersectsFrustum(TerrainPreviewFrame frame,
            Matrix4fc viewProjection, TerrainSectionKey key) {
        float x = key.minBlockX() - frame.originX();
        float y = key.minBlockY() - frame.originY();
        float z = key.minBlockZ() - frame.originZ();
        return intersectsClip(viewProjection,
                x, y, z, x + TerrainSectionKey.SIZE, y + TerrainSectionKey.SIZE,
                z + TerrainSectionKey.SIZE);
    }

    private static boolean intersectsClip(Matrix4fc matrix,
            float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int outsidePlanes = 0x3F;
        for (int corner = 0; corner < 8 && outsidePlanes != 0; corner++) {
            float x = (corner & 4) == 0 ? minX : maxX;
            float y = (corner & 2) == 0 ? minY : maxY;
            float z = (corner & 1) == 0 ? minZ : maxZ;
            float clipX = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
            float clipY = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
            float clipZ = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
            float clipW = matrix.m03() * x + matrix.m13() * y + matrix.m23() * z + matrix.m33();
            int cornerOutside = 0;
            if (clipX < -clipW) cornerOutside |= 1;
            if (clipX > clipW) cornerOutside |= 2;
            if (clipY < -clipW) cornerOutside |= 4;
            if (clipY > clipW) cornerOutside |= 8;
            if (clipZ < -clipW) cornerOutside |= 16;
            if (clipZ > clipW) cornerOutside |= 32;
            outsidePlanes &= cornerOutside;
        }
        return outsidePlanes == 0;
    }

    private void submitCompilation(TerrainPreviewFrame frame,
            TerrainBlockSectionSnapshot snapshot) {
        CompilationRequest request = new CompilationRequest(compilationEpoch, frame, snapshot,
            modelSet, Minecraft.getInstance().getModelManager().getFluidStateModelSet(),
            Minecraft.getInstance().getBlockColors());
        activeCompilation = request;
        compilationExecutor.execute(() -> {
            TerrainPreviewSectionCompiler.CompiledCpuSection compiled = null;
            Throwable failure = null;
            try {
                compiled = TerrainPreviewSectionCompiler.compile(request.frame, request.source,
                    request.models, request.fluidModels, request.blockColors);
            } catch (Throwable caught) {
                failure = caught;
            }
            if (closed) {
                if (compiled != null) {
                    compiled.close();
                }
                return;
            }
            CompilationOutcome outcome = new CompilationOutcome(request, compiled, failure);
            completedCompilations.add(outcome);
            if (closed && completedCompilations.remove(outcome)) {
                outcome.closeCompiled();
            }
        });
    }

    private void consumeCompletedCompilation(TerrainPreviewFrame frame) {
        CompilationOutcome outcome = completedCompilations.poll();
        if (outcome == null) {
            return;
        }
        if (activeCompilation == outcome.request) {
            activeCompilation = null;
        }
        boolean current = TerrainCompilationAdmission.isCurrent(frame, outcome.request.source,
            outcome.request.epoch, compilationEpoch);
        if (!current) {
            outcome.closeCompiled();
            return;
        }
        if (outcome.failure != null) {
            if (outcome.failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            TerrainPreviewRenderDiagnostics.recordFailure();
            failedSources.put(outcome.request.source.section(), outcome.request.source);
            LOGGER.warn("无法编译地形预览 section {}，保留旧网格",
                    outcome.request.source.section(), outcome.failure);
            TerrainPreviewManager.markCompiled(outcome.request.frame.generation(),
                    outcome.request.source);
            return;
        }
        try (TerrainPreviewSectionCompiler.CompiledCpuSection compiled = outcome.compiled) {
            try {
                GpuSection replacement = GpuSection.upload(compiled, sharedBuffers);
                boolean materialLod = compiled.source().blocks().stream()
                        .anyMatch(block -> block.cellSize() > 1);
                boolean translucent = compiled.layers().containsKey(ChunkSectionLayer.TRANSLUCENT);
                TerrainPreviewRenderDiagnostics.recordSectionUpload(materialLod, translucent);
                GpuSection old = sections.put(outcome.request.source.section(), replacement);
                if (old != null) {
                    old.close();
                }
                residentRevision++;
                exhaustedCompilationFrame = null;
                exhaustedCompilationViewProjection = null;
                cachedRenderPlan = null;
                failedSources.remove(outcome.request.source.section());
                TerrainPreviewManager.markCompiled(outcome.request.frame.generation(),
                        outcome.request.source);
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                if (failure instanceof Error fatal) {
                    throw fatal;
                }
                TerrainPreviewRenderDiagnostics.recordFailure();
                failedSources.put(outcome.request.source.section(), outcome.request.source);
                LOGGER.warn("无法上传地形预览 section {}，跳过该 section",
                    outcome.request.source.section(), failure);
                TerrainPreviewManager.markCompiled(outcome.request.frame.generation(),
                    outcome.request.source);
            }
        }
    }

        private void renderLayers(TerrainPreviewFrame frame, Matrix4fc modelView,
            Matrix4fc viewProjection) {
        Minecraft minecraft = Minecraft.getInstance();
        AbstractTexture atlas = minecraft.getTextureManager().getTexture(BLOCK_ATLAS_LOCATION);
        int atlasWidth = atlas.getTexture().getWidth(0);
        int atlasHeight = atlas.getTexture().getHeight(0);
        Vec3 mainCameraPosition = minecraft.gameRenderer.getMainCamera().position();
        TerrainPreviewCoordinateTransform.Frame terrainTransform =
                TerrainPreviewCoordinateTransform.create(modelView,
                        mainCameraPosition.x, mainCameraPosition.y, mainCameraPosition.z);
        if (resortTranslucentQuads(frame, modelView, viewProjection)) {
            residentRevision++;
            cachedRenderPlan = null;
            cachedUniformPlan = null;
        }
        RenderPlan plan = renderPlan(frame, modelView, viewProjection);
        List<GpuSection> visible = plan.visible;
        logCoverage(frame, visible.size());
        GpuBufferSlice[] chunkInfos = createChunkInfos(
            frame, terrainTransform, atlasWidth, atlasHeight, plan);
        try {
            drawLayers(minecraft, atlas, plan, chunkInfos);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            throw new TerrainPreviewRenderFailure(failure);
        }
    }

    private boolean resortTranslucentQuads(TerrainPreviewFrame frame, Matrix4fc modelView,
            Matrix4fc viewProjection) {
        boolean changed = false;
        for (GpuSection section : sections.values()) {
            GpuLayer layer = section.layers.get(ChunkSectionLayer.TRANSLUCENT);
            if (layer == null || layer.sortState == null || layer.sortedFor(modelView)
                    || !intersectsFrustum(frame, viewProjection, section.key)) {
                continue;
            }
            float sectionX = section.key.minBlockX() - frame.originX();
            float sectionY = section.key.minBlockY() - frame.originY();
            float sectionZ = section.key.minBlockZ() - frame.originZ();
            VertexSorting sorting = VertexSorting.byDistance(point -> {
                return TerrainTranslucentSortPolicy.viewDistanceSquared(modelView,
                        sectionX, sectionY, sectionZ, point.x(), point.y(), point.z());
            });
            layer.resort(sorting, modelView);
            changed = true;
        }
        return changed;
    }

    private void disableForSession(String message, Throwable failure) {
        disabledForSession = true;
        if (!failureLogged) {
            failureLogged = true;
            LOGGER.warn("{}; disabling terrain preview until the session is reset", message, failure);
        }
        clear();
        if (sharedBuffers != null) {
            sharedBuffers.close();
            sharedBuffers = null;
        }
    }

    private RenderPlan renderPlan(TerrainPreviewFrame frame, Matrix4fc modelView,
            Matrix4fc viewProjection) {
        RenderPlan cached = cachedRenderPlan;
        if (cached != null && cached.residentRevision == residentRevision
                && cached.frame == frame && cached.modelView.equals(modelView)
                && cached.viewProjection.equals(viewProjection)) {
            renderPlanHits++;
            return cached;
        }
        renderPlanBuilds++;
        List<GpuSection> visible = new ArrayList<>();
        for (GpuSection section : sections.values()) {
            if (intersectsFrustum(frame, viewProjection, section.key)) {
                visible.add(section);
            }
        }
        visible.sort(Comparator.comparingDouble(section -> viewDepth(modelView, frame, section)));
        EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> draws =
                new EnumMap<>(ChunkSectionLayer.class);
        EnumMap<ChunkSectionLayer, Integer> largestSequentialIndices =
                new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            List<RenderPass.Draw<GpuBufferSlice[]>> layerDraws = new ArrayList<>();
            int largestSequentialIndexCount = 0;
            for (int i = 0; i < visible.size(); i++) {
                GpuLayer mesh = visible.get(i).layers.get(layer);
                if (mesh == null) {
                    continue;
                }
                LayerBufferSlice slice = sharedBuffers.slice(layer, mesh);
                if (slice == null) {
                    continue;
                }
                VertexFormat vertexFormat = layer.pipeline().getVertexFormat();
                int baseVertex = Math.toIntExact(slice.vertexOffset() / vertexFormat.getVertexSize());
                int firstIndex = mesh.customIndices
                        ? Math.toIntExact(slice.indexOffset() / mesh.indexType.bytes) : 0;
                if (!mesh.customIndices) {
                    largestSequentialIndexCount = Math.max(largestSequentialIndexCount, mesh.indexCount);
                }
                int uboIndex = i;
                layerDraws.add(new RenderPass.Draw<>(0, slice.vertexBuffer(), slice.indexBuffer(),
                        mesh.customIndices ? mesh.indexType : null, firstIndex, mesh.indexCount,
                        baseVertex, (ubos, uploader) -> uploader.upload("ChunkSection", ubos[uboIndex])));
            }
            draws.put(layer, List.copyOf(layerDraws));
            largestSequentialIndices.put(layer, largestSequentialIndexCount);
        }
        cached = new RenderPlan(frame, residentRevision, new Matrix4f(modelView),
                new Matrix4f(viewProjection), List.copyOf(visible), draws,
                largestSequentialIndices);
        cachedRenderPlan = cached;
        return cached;
    }

    private void logCoverage(TerrainPreviewFrame frame, int visibleSections) {
        if (!LOGGER.isTraceEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (frame.generation() == lastCoverageGeneration
            && now - lastCoverageLogNanos < COVERAGE_LOG_INTERVAL_NANOS) {
            return;
        }
        lastCoverageGeneration = frame.generation();
        lastCoverageLogNanos = now;
        long unknown = frame.overviewCells().stream()
            .filter(cell -> cell.material() == com.zhongbai233.net_music_can_play_bili.terrain.core
                .TerrainCellSample.RenderCategory.UNKNOWN)
            .count();
        long overview = frame.overviewCells().size() - unknown;
        LOGGER.trace("地形预览 hardRange 覆盖: generation={}, bounds={}, nearCpu={}, overviewCells={}, wireSegments={}, unknownSections={}, "
                + "capturePending={}, sampledSections={}, resident={}, visible={}, compilerActive={}",
            frame.generation(), frame.bounds(), frame.fullDetailSections().size(), overview,
            frame.wireframeSegments().size(), unknown,
            frame.pendingSections(), frame.sampledSections(), sections.size(), visibleSections,
            activeCompilation != null);
    }

    private GpuBufferSlice[] createChunkInfos(TerrainPreviewFrame frame,
            TerrainPreviewCoordinateTransform.Frame transform,
            int atlasWidth, int atlasHeight, RenderPlan plan) {
        List<GpuSection> visible = plan.visible;
        if (visible.isEmpty()) {
            return new GpuBufferSlice[0];
        }
        UniformPlan uniforms = cachedUniformPlan;
        if (uniforms == null || uniforms.renderPlan != plan
                || uniforms.atlasWidth != atlasWidth || uniforms.atlasHeight != atlasHeight
                || uniforms.cameraBlockX != transform.cameraBlockX()
                || uniforms.cameraBlockY != transform.cameraBlockY()
                || uniforms.cameraBlockZ != transform.cameraBlockZ()
                || !uniforms.modelView.equals(transform.modelView())) {
            DynamicUniforms.ChunkSectionInfo[] infos =
                    new DynamicUniforms.ChunkSectionInfo[visible.size()];
            for (int i = 0; i < visible.size(); i++) {
                GpuSection section = visible.get(i);
                infos[i] = new DynamicUniforms.ChunkSectionInfo(transform.modelView(),
                        transform.encodedSectionX(section.key.minBlockX() - frame.originX()),
                        transform.encodedSectionY(section.key.minBlockY() - frame.originY()),
                        transform.encodedSectionZ(section.key.minBlockZ() - frame.originZ()),
                        1.0F, atlasWidth, atlasHeight);
            }
            uniforms = new UniformPlan(plan, new Matrix4f(transform.modelView()),
                    transform.cameraBlockX(), transform.cameraBlockY(), transform.cameraBlockZ(),
                    atlasWidth, atlasHeight, infos);
            cachedUniformPlan = uniforms;
        }
        return RenderSystem.getDynamicUniforms().writeChunkSections(uniforms.infos);
    }

    private static double viewDistanceSquared(Matrix4fc modelView, TerrainPreviewFrame frame,
            TerrainSectionKey key) {
        float x = key.minBlockX() - frame.originX() + 8.0F;
        float y = key.minBlockY() - frame.originY() + 8.0F;
        float z = key.minBlockZ() - frame.originZ() + 8.0F;
        double viewX = modelView.m00() * x + modelView.m10() * y + modelView.m20() * z + modelView.m30();
        double viewY = modelView.m01() * x + modelView.m11() * y + modelView.m21() * z + modelView.m31();
        double viewZ = modelView.m02() * x + modelView.m12() * y + modelView.m22() * z + modelView.m32();
        return viewX * viewX + viewY * viewY + viewZ * viewZ;
    }

    private static double viewDepth(Matrix4fc modelView, TerrainPreviewFrame frame,
            GpuSection section) {
        float x = section.key.minBlockX() - frame.originX() + 8.0F;
        float y = section.key.minBlockY() - frame.originY() + 8.0F;
        float z = section.key.minBlockZ() - frame.originZ() + 8.0F;
        return modelView.m02() * x + modelView.m12() * y
            + modelView.m22() * z + modelView.m32();
    }

    private void drawLayers(Minecraft minecraft, AbstractTexture atlas, RenderPlan plan,
            GpuBufferSlice[] chunkInfos) {
        var color = RenderSystem.outputColorTextureOverride;
        var depth = RenderSystem.outputDepthTextureOverride;
        if (color == null || depth == null) {
            return;
        }
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "NCPB terrain preview shared sections", color, OptionalInt.empty(),
                depth, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("Sampler0", atlas.getTextureView(), atlas.getSampler());
            pass.bindTexture("Sampler2", minecraft.gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                List<RenderPass.Draw<GpuBufferSlice[]>> draws = plan.draws.get(layer);
                if (draws.isEmpty()) {
                    continue;
                }
                int largestSequentialIndexCount = plan.largestSequentialIndices.get(layer);
                RenderSystem.AutoStorageIndexBuffer sequential =
                        RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
                GpuBuffer defaultIndices = largestSequentialIndexCount == 0
                        ? null : sequential.getBuffer(largestSequentialIndexCount);
                VertexFormat.IndexType defaultIndexType = largestSequentialIndexCount == 0
                        ? null : sequential.type();
                pass.setPipeline(layer.pipeline());
                pass.drawMultipleIndexed(draws, defaultIndices, defaultIndexType,
                    CHUNK_SECTION_UNIFORM, chunkInfos);
            }
        }
    }

    void clear() {
        compilationEpoch++;
        activeCompilation = null;
        sections.values().forEach(section -> section.close());
        sections.clear();
        failedSources.clear();
        synchronizedBounds = null;
        exhaustedCompilationFrame = null;
        exhaustedCompilationViewProjection = null;
        residentRevision++;
        cachedRenderPlan = null;
        cachedUniformPlan = null;
        lastCoverageLogNanos = 0L;
        lastCoverageGeneration = Long.MIN_VALUE;
        if (!disabledForSession) {
            failureLogged = false;
        }
        CompilationOutcome outcome;
        while ((outcome = completedCompilations.poll()) != null) {
            outcome.closeCompiled();
        }
    }

    /** 预览会话结束时释放共享 GPU heap；executor 保留，下一次打开时按需重建。 */
    void releaseSession() {
        if (sharedBuffers == null && sections.isEmpty() && activeCompilation == null
                && completedCompilations.isEmpty()) {
            return;
        }
        clear();
        if (sharedBuffers != null) {
            sharedBuffers.close();
            sharedBuffers = null;
        }
        disabledForSession = false;
        failureLogged = false;
        sessionReleases++;
    }

    @Override
    public void close() {
        closed = true;
        releaseSession();
        LOGGER.debug("地形预览稳态缓存: planBuilds={}, planHits={}, sessionReleases={}",
            renderPlanBuilds, renderPlanHits, sessionReleases);
        compilationExecutor.shutdownNow();
    }

    private static ThreadFactory terrainCompilerThreadFactory() {
        return task -> {
            Thread thread = new Thread(task, "NCPB terrain section compiler");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        };
    }

        private record CompilationRequest(long epoch, TerrainPreviewFrame frame,
            TerrainBlockSectionSnapshot source, BlockStateModelSet models,
            FluidStateModelSet fluidModels, BlockColors blockColors) {
    }

    private record CompilationOutcome(CompilationRequest request,
            TerrainPreviewSectionCompiler.CompiledCpuSection compiled, Throwable failure) {
        private CompilationOutcome {
            if ((compiled == null) == (failure == null)) {
                throw new IllegalArgumentException("compilation outcome must contain exactly one result");
            }
        }

        private void closeCompiled() {
            if (compiled != null) {
                compiled.close();
            }
        }
    }

    private static final class TerrainPreviewRenderFailure extends RuntimeException {
        private TerrainPreviewRenderFailure(Throwable cause) {
            super(cause);
        }
    }

    private static final class GpuSection implements AutoCloseable {
        private final TerrainSectionKey key;
        private final WeakReference<TerrainBlockSectionSnapshot> source;
        private final Map<ChunkSectionLayer, GpuLayer> layers;

        private GpuSection(TerrainSectionKey key, TerrainBlockSectionSnapshot source,
                Map<ChunkSectionLayer, GpuLayer> layers) {
            this.key = key;
            this.source = new WeakReference<>(source);
            this.layers = layers;
        }

        private static GpuSection upload(TerrainPreviewSectionCompiler.CompiledCpuSection compiled,
                SharedTerrainBuffers sharedBuffers) {
            Map<ChunkSectionLayer, GpuLayer> layers = new EnumMap<>(ChunkSectionLayer.class);
            try {
                for (Map.Entry<ChunkSectionLayer, MeshData> entry : compiled.layers().entrySet()) {
                    GpuLayer layer = GpuLayer.create(entry.getValue(),
                            compiled.sortStates().get(entry.getKey()));
                    sharedBuffers.upload(entry.getKey(), layer, entry.getValue());
                    layers.put(entry.getKey(), layer);
                }
                return new GpuSection(compiled.source().section(), compiled.source(), layers);
            } catch (Throwable failure) {
                layers.values().forEach(layer -> layer.close());
                throw failure;
            }
        }

        @Override
        public void close() {
            layers.values().forEach(layer -> layer.close());
            layers.clear();
        }
    }

    private static final class GpuLayer implements AutoCloseable {
        private final int indexCount;
        private final VertexFormat.IndexType indexType;
        private final boolean customIndices;
        private final MeshData.SortState sortState;
        private SharedTerrainBuffers owner;
        private ChunkSectionLayer ownerLayer;
        private Matrix4f lastSortModelView;

        private GpuLayer(int indexCount, VertexFormat.IndexType indexType,
                boolean customIndices, MeshData.SortState sortState) {
            this.indexCount = indexCount;
            this.indexType = indexType;
            this.customIndices = customIndices;
            this.sortState = sortState;
        }

        private static GpuLayer create(MeshData mesh, MeshData.SortState sortState) {
            return new GpuLayer(mesh.drawState().indexCount(), mesh.drawState().indexType(),
                    mesh.indexBuffer() != null, sortState);
        }

        private boolean sortedFor(Matrix4fc modelView) {
            return !TerrainTranslucentSortPolicy.needsResort(lastSortModelView, modelView);
        }

        private void resort(VertexSorting sorting, Matrix4fc modelView) {
            if (owner == null || ownerLayer != ChunkSectionLayer.TRANSLUCENT || sortState == null) {
                return;
            }
            owner.resort(this, sortState, sorting);
            lastSortModelView = new Matrix4f(modelView);
            TerrainPreviewRenderDiagnostics.recordTranslucentResort();
        }

        @Override
        public void close() {
            if (owner != null) {
                owner.remove(ownerLayer, this);
                owner = null;
                ownerLayer = null;
            }
        }
    }

    private static final class SharedTerrainBuffers implements AutoCloseable {
        private static final int VERTEX_HEAP_BYTES = 16 * 1024 * 1024;
        private static final int VERTEX_STAGING_BYTES = 8 * 1024 * 1024;
        private static final int INDEX_HEAP_BYTES = 8 * 1024 * 1024;
        private static final int INDEX_STAGING_BYTES = 2 * 1024 * 1024;
        private final Map<ChunkSectionLayer, UberGpuBuffer<GpuLayer>> vertices =
                new EnumMap<>(ChunkSectionLayer.class);
        private final UberGpuBuffer<GpuLayer> translucentIndices;

        private SharedTerrainBuffers() {
            var device = RenderSystem.getDevice();
            GraphicsWorkarounds workarounds = GraphicsWorkarounds.get(device);
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                vertices.put(layer, new UberGpuBuffer<>("NCPB terrain " + layer.label(),
                        GpuBuffer.USAGE_VERTEX, VERTEX_HEAP_BYTES,
                        layer.pipeline().getVertexFormat().getVertexSize(), device,
                        VERTEX_STAGING_BYTES, workarounds));
            }
            translucentIndices = new UberGpuBuffer<>("NCPB terrain translucent indices",
                    GpuBuffer.USAGE_INDEX, INDEX_HEAP_BYTES, 8, device,
                    INDEX_STAGING_BYTES, workarounds);
        }

        private void upload(ChunkSectionLayer layer, GpuLayer key, MeshData mesh) {
            UberGpuBuffer<GpuLayer> vertexBuffer = vertices.get(layer);
            boolean vertexAllocated = false;
            boolean indexAllocated = false;
            try {
                if (!vertexBuffer.addAllocation(key, null, mesh.vertexBuffer())) {
                    throw new IllegalStateException("terrain vertex staging buffer exhausted");
                }
                var device = RenderSystem.getDevice();
                var encoder = device.createCommandEncoder();
                vertexBuffer.uploadStagedAllocations(device, encoder);
                vertexAllocated = true;
                if (mesh.indexBuffer() != null) {
                    if (layer != ChunkSectionLayer.TRANSLUCENT) {
                        throw new IllegalStateException("custom terrain indices outside translucent layer");
                    }
                    if (!translucentIndices.addAllocation(key, null, mesh.indexBuffer())) {
                        throw new IllegalStateException("terrain index staging buffer exhausted");
                    }
                    translucentIndices.uploadStagedAllocations(device, encoder);
                    indexAllocated = true;
                }
                key.owner = this;
                key.ownerLayer = layer;
            } catch (Throwable failure) {
                if (indexAllocated || mesh.indexBuffer() != null) {
                    translucentIndices.removeAllocation(key);
                }
                if (vertexAllocated) {
                    vertexBuffer.removeAllocation(key);
                }
                throw failure;
            }
        }

        private LayerBufferSlice slice(ChunkSectionLayer layer, GpuLayer key) {
            UberGpuBuffer<GpuLayer> vertexBuffer = vertices.get(layer);
            TlsfAllocator.Allocation vertex = vertexBuffer.getAllocation(key);
            if (vertex == null) {
                return null;
            }
            TlsfAllocator.Allocation index = key.customIndices
                    ? translucentIndices.getAllocation(key) : null;
            if (key.customIndices && index == null) {
                return null;
            }
            return new LayerBufferSlice(vertexBuffer.getGpuBuffer(vertex),
                    vertex.getOffsetFromHeap(), index == null ? null
                            : translucentIndices.getGpuBuffer(index),
                    index == null ? 0L : index.getOffsetFromHeap());
        }

        private void remove(ChunkSectionLayer layer, GpuLayer key) {
            vertices.get(layer).removeAllocation(key);
            if (key.customIndices) {
                translucentIndices.removeAllocation(key);
            }
        }

        private void resort(GpuLayer key, MeshData.SortState sortState, VertexSorting sorting) {
            if (!key.customIndices) {
                return;
            }
            int bytes = Math.max(256, key.indexCount * key.indexType.bytes);
            try (ByteBufferBuilder storage = new ByteBufferBuilder(bytes)) {
                ByteBufferBuilder.Result result = sortState.buildSortedIndexBuffer(storage, sorting);
                if (result == null) {
                    return;
                }
                try (result) {
                    translucentIndices.removeAllocation(key);
                    if (!translucentIndices.addAllocation(key, null, result.byteBuffer())) {
                        throw new IllegalStateException("terrain translucent index staging buffer exhausted");
                    }
                    var device = RenderSystem.getDevice();
                    translucentIndices.uploadStagedAllocations(device, device.createCommandEncoder());
                } catch (Throwable failure) {
                    translucentIndices.removeAllocation(key);
                    throw failure;
                }
            }
        }

        @Override
        public void close() {
            vertices.values().forEach(buffer -> buffer.close());
            vertices.clear();
            translucentIndices.close();
        }
    }

    private record LayerBufferSlice(GpuBuffer vertexBuffer, long vertexOffset,
            GpuBuffer indexBuffer, long indexOffset) {
    }

        private record RenderPlan(TerrainPreviewFrame frame, long residentRevision,
            Matrix4f modelView, Matrix4f viewProjection, List<GpuSection> visible,
            EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> draws,
            EnumMap<ChunkSectionLayer, Integer> largestSequentialIndices) {
        }

            private record UniformPlan(RenderPlan renderPlan, Matrix4f modelView,
                int cameraBlockX, int cameraBlockY, int cameraBlockZ,
                int atlasWidth, int atlasHeight, DynamicUniforms.ChunkSectionInfo[] infos) {
            }
}
