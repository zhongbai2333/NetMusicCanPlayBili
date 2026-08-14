package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.bench.api.neoforge.client.BenchGuiSession;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainHardRangeBounds;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewFrame;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.client.renderer.gui.TerrainPreviewRenderDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;

import java.io.IOException;
import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

final class TerrainLodRoundTripScenario implements BenchClientScenario {
    static final boolean COMPAT_MATRIX = Boolean.getBoolean("ncpb.terrain.compat_matrix");
    private static final Set<String> REQUIRED_COMPAT_MODS = Set.of(
            "iris", "sodium", "biomesoplenty", "glitchcore", "terrablender",
            "colossalchests", "cyclopscore");
    private static final String RESOURCE_PACK_FILE = "Accurate_textures_26.1.2.zip";
    private static final BenchMetricDescriptor MATERIAL_4_CELLS = new BenchMetricDescriptor(
            "ncpb.terrain.material_4_cells", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor MATERIAL_8_CELLS = new BenchMetricDescriptor(
            "ncpb.terrain.material_8_cells", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor TINTED_CELLS = new BenchMetricDescriptor(
            "ncpb.terrain.tinted_cells", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor BLOCK_ENTITIES = new BenchMetricDescriptor(
            "ncpb.terrain.block_entities", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor MATERIAL_UPLOADS = new BenchMetricDescriptor(
            "ncpb.terrain.material_uploads", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor TRANSLUCENT_UPLOADS = new BenchMetricDescriptor(
            "ncpb.terrain.translucent_uploads", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor TRANSLUCENT_RESORTS = new BenchMetricDescriptor(
            "ncpb.terrain.translucent_resorts", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor BLOCK_ENTITY_SUBMISSIONS = new BenchMetricDescriptor(
            "ncpb.terrain.block_entity_submissions", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor RENDER_FAILURES = new BenchMetricDescriptor(
            "ncpb.terrain.render_failures", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor EXTERNAL_TINTED_CELLS = new BenchMetricDescriptor(
            "ncpb.terrain.external_tinted_cells", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor EXTERNAL_FLUID_CELLS = new BenchMetricDescriptor(
            "ncpb.terrain.external_fluid_cells", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor EXTERNAL_BLOCK_ENTITIES = new BenchMetricDescriptor(
            "ncpb.terrain.external_block_entities", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor RESOURCE_PACK_ACTIVE = new BenchMetricDescriptor(
            "ncpb.terrain.resource_pack_active", "boolean", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor SHADER_PACK_ACTIVE = new BenchMetricDescriptor(
            "ncpb.terrain.shader_pack_active", "boolean", MetricDirection.NEUTRAL);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean setupComplete = new AtomicBoolean();
    private final CopyOnWriteArrayList<BlockPos> fixturePositions = new CopyOnWriteArrayList<>();
    private BlockPos origin;
    private BlockPos chestPos;
    private BlockPos midGlassPos;
    private BlockPos farTintPos;
    private BlockPos customFluidPos;
    private Block customBlockEntityBlock;
    private Block customTintBlock;
    private Block customFluidBlock;
    private com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds bounds;
    private TerrainPreviewRenderDiagnostics.Snapshot renderBaseline;
    private TerrainPipBenchScreen screen;
    private BenchGuiSession gui;
    private CompletableFuture<Path> firstScreenshot;
    private CompletableFuture<Path> secondScreenshot;
    private int phase;
    private int releaseTicks;
    private long generation;
    private long resortsBeforeRotation;
    private int sampledBeforeReopen;
    private boolean cacheRoundTripDone;
    private boolean resourcePackVerified;
    private boolean shaderPackVerified;
    private long maxExternalTinted;
    private long maxExternalFluids;
    private long maxExternalBlockEntities;
    private long maxTranslucentUploads;
    private UUID playerId;

    @Override
    public void setup(BenchClientContext context) {
        origin = context.player().blockPosition();
        playerId = context.player().getUUID();
        bounds = TerrainHardRangeBounds.around(origin.getX(), origin.getY(), origin.getZ(),
                56.0D, 16.0D, 56.0D, context.level().getMinY(), context.level().getMaxY());
        int fixtureY = Math.min(context.level().getMaxY() - 16, origin.getY() + 8);
        chestPos = new BlockPos(origin.getX() + 2, origin.getY() + 2, origin.getZ() + 2);
        midGlassPos = new BlockPos(alignDown(origin.getX() + 24, 4), alignDown(fixtureY, 4),
                alignDown(origin.getZ(), 4));
        farTintPos = new BlockPos(alignDown(origin.getX() + 48, 8), alignDown(fixtureY, 8),
                alignDown(origin.getZ(), 8));
        customFluidPos = origin.offset(7, 3, 3);
        if (COMPAT_MATRIX) {
            verifyCompatibilityMods();
            customBlockEntityBlock = requiredBlock("colossalchests:uncolossal_chest");
            customTintBlock = requiredBlock("biomesoplenty:palm_leaves");
            customFluidBlock = requiredBlock("biomesoplenty:blood");
            resourcePackVerified = verifyAccurateTexturesResourcePack(context);
        }
        renderBaseline = TerrainPreviewRenderDiagnostics.snapshot();
        TerrainPreviewManager.clear();
        prepareFixture(context);
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        throwIfFailed();
        if (!setupComplete.get() || !fixtureVisible(context) || !context.environment().readiness().ready()) {
            return BenchClientStepResult.CONTINUE;
        }
        if (screen == null) {
            screen = new TerrainPipBenchScreen(origin);
            context.minecraft().setScreen(screen);
            gui = context.automation().beginGuiSession(TerrainPipBenchScreen.class);
        }
        return context.minecraft().screen == screen && context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        throwIfFailed();
        Vector3d fixedCore = new Vector3d(0.5D, 0.5D, 0.5D);
        TerrainPreviewManager.update(context.level(), origin, bounds, fixedCore);
        TerrainPreviewFrame frame = TerrainPreviewManager.frame();
        if (generation == 0L) {
            generation = frame.generation();
        } else if (frame.generation() != generation) {
            throw new AssertionError("Fixed terrain core unexpectedly rebuilt: "
                    + generation + " -> " + frame.generation());
        }
        long material4 = materialCells(frame, 4);
        long material8 = materialCells(frame, 8);
        long tinted = frame.fullDetailSections().stream().flatMap(section -> section.blocks().stream())
                .filter(block -> !block.tintLayers().isEmpty()).count();
        long externalTinted = externalTintedCells(frame);
        long externalFluids = externalFluidCells(frame);
        long externalBlockEntities = externalBlockEntities(frame);
        maxExternalTinted = Math.max(maxExternalTinted, externalTinted);
        maxExternalFluids = Math.max(maxExternalFluids, externalFluids);
        maxExternalBlockEntities = Math.max(maxExternalBlockEntities, externalBlockEntities);
        if (COMPAT_MATRIX && IrisShaderpackCompat.isShaderPackInUse()) {
            shaderPackVerified = true;
        }
        TerrainPreviewRenderDiagnostics.Snapshot rendered = TerrainPreviewRenderDiagnostics.snapshot()
                .deltaFrom(renderBaseline);
        maxTranslucentUploads = Math.max(maxTranslucentUploads, rendered.translucentSectionUploads());
        context.metrics().record(MATERIAL_4_CELLS, material4);
        context.metrics().record(MATERIAL_8_CELLS, material8);
        context.metrics().record(TINTED_CELLS, tinted);
        context.metrics().record(BLOCK_ENTITIES, frame.blockEntities().size());
        context.metrics().record(MATERIAL_UPLOADS, rendered.materialSectionUploads());
        context.metrics().record(TRANSLUCENT_UPLOADS, rendered.translucentSectionUploads());
        context.metrics().record(TRANSLUCENT_RESORTS, rendered.translucentResorts());
        context.metrics().record(BLOCK_ENTITY_SUBMISSIONS, rendered.blockEntitySubmissions());
        context.metrics().record(RENDER_FAILURES, rendered.failures());
        context.metrics().record(EXTERNAL_TINTED_CELLS, externalTinted);
        context.metrics().record(EXTERNAL_FLUID_CELLS, externalFluids);
        context.metrics().record(EXTERNAL_BLOCK_ENTITIES, externalBlockEntities);
        context.metrics().record(RESOURCE_PACK_ACTIVE, resourcePackVerified ? 1L : 0L);
        context.metrics().record(SHADER_PACK_ACTIVE, shaderPackVerified ? 1L : 0L);
        if (rendered.failures() != 0L) {
            throw new AssertionError("Terrain PIP reported " + rendered.failures() + " render failure(s)");
        }

        boolean compatReady = !COMPAT_MATRIX || resourcePackVerified && shaderPackVerified
                && externalTinted > 0L && externalFluids > 0L && externalBlockEntities > 0L
                && rendered.translucentSectionUploads() >= 2L;
        if (phase == 0 && material4 > 0L && material8 > 0L && tinted > 0L && compatReady
                && !frame.blockEntities().isEmpty() && rendered.materialSectionUploads() > 0L
                && rendered.translucentSectionUploads() > 0L && rendered.blockEntitySubmissions() > 0L) {
            sampledBeforeReopen = frame.sampledSections();
            TerrainPreviewManager.close(origin);
            TerrainPreviewManager.update(context.level(), origin, bounds, fixedCore);
            TerrainPreviewFrame reopened = TerrainPreviewManager.frame();
            if (reopened.generation() != generation
                    || reopened.sampledSections() < sampledBeforeReopen
                    || materialCells(reopened, 4) == 0L || materialCells(reopened, 8) == 0L) {
                throw new AssertionError("Parked terrain cache did not restore material LOD immediately");
            }
            cacheRoundTripDone = true;
            firstScreenshot = context.automation().captureScreenshot("terrain-material-pip-before",
                    com.zhongbai233.bench.api.neoforge.client.BenchCaptureOptions.immediate());
            phase = 1;
        } else if (phase == 1 && completedScreenshot(firstScreenshot)) {
            resortsBeforeRotation = rendered.translucentResorts();
            screen.setAngleDegrees(145.0D);
            phase = 2;
        } else if (phase == 2 && rendered.translucentResorts() > resortsBeforeRotation) {
            secondScreenshot = context.automation().captureScreenshot("terrain-material-pip-after",
                    com.zhongbai233.bench.api.neoforge.client.BenchCaptureOptions.immediate());
            phase = 3;
        } else if (phase == 3 && completedScreenshot(secondScreenshot)) {
            screen.setRenderTerrain(false);
            TerrainPreviewManager.clear();
            phase = 4;
        } else if (phase == 4 && ++releaseTicks >= 3) {
            context.minecraft().setScreen(null);
            if (gui != null) {
                gui.close();
            }
            phase = 5;
            return BenchClientStepResult.COMPLETE;
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        TerrainPreviewRenderDiagnostics.Snapshot rendered = TerrainPreviewRenderDiagnostics.snapshot()
                .deltaFrom(renderBaseline);
        if (phase != 5 || !cacheRoundTripDone || context.minecraft().screen != null
                || gui == null || gui.active()
                || !completedScreenshot(firstScreenshot) || !completedScreenshot(secondScreenshot)
                || rendered.materialSectionUploads() == 0L
                || rendered.translucentSectionUploads() == 0L
                || rendered.translucentResorts() <= resortsBeforeRotation
                || rendered.blockEntitySubmissions() == 0L || rendered.failures() != 0L
                || COMPAT_MATRIX && (!resourcePackVerified || !shaderPackVerified
                        || maxExternalTinted == 0L || maxExternalFluids == 0L
                        || maxExternalBlockEntities == 0L || maxTranslucentUploads < 2L)) {
            throw new AssertionError("Terrain material PIP did not converge: phase=" + phase
                    + ", cacheRoundTrip=" + cacheRoundTripDone + ", diagnostics=" + rendered
                    + ", externalTinted=" + maxExternalTinted + ", externalFluids=" + maxExternalFluids
                    + ", externalBlockEntities=" + maxExternalBlockEntities
                    + ", maxTranslucentUploads=" + maxTranslucentUploads);
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        if (context.minecraft().screen == screen) {
            context.minecraft().setScreen(null);
        }
        if (gui != null) {
            gui.close();
        }
        TerrainPreviewManager.clear();
        var server = context.minecraft().getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && player.level() instanceof ServerLevel level) {
                    fixturePositions.forEach(pos -> level.setBlockAndUpdate(pos,
                            Blocks.AIR.defaultBlockState()));
                }
            });
        }
    }

    private void prepareFixture(BenchClientContext context) {
        var server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            failure.compareAndSet(null, new IllegalStateException("Integrated server is unavailable"));
            return;
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || !(player.level() instanceof ServerLevel level)) {
                    throw new IllegalStateException("Integrated server player is unavailable");
                }
                placeFixture(level, chestPos, COMPAT_MATRIX
                        ? customBlockEntityBlock.defaultBlockState() : Blocks.CHEST.defaultBlockState());
                for (int x = 0; x < 4; x++) {
                    for (int z = 0; z < 4; z++) {
                        placeFixture(level, midGlassPos.offset(x, 0, z),
                                Blocks.BLUE_STAINED_GLASS.defaultBlockState());
                    }
                }
                for (int x = 0; x < 8; x++) {
                    for (int z = 0; z < 8; z++) {
                        placeFixture(level, farTintPos.offset(x, 0, z), COMPAT_MATRIX
                                ? customTintBlock.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState());
                    }
                }
                if (COMPAT_MATRIX) {
                    placeFluidBasin(level);
                }
                setupComplete.set(true);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
    }

    private void placeFixture(ServerLevel level, BlockPos pos, BlockState state) {
        if (!level.getBlockState(pos).isAir()) {
            throw new AssertionError("Terrain bench fixture would overwrite a non-air block at " + pos);
        }
        fixturePositions.add(pos.immutable());
        level.setBlockAndUpdate(pos, state);
    }

    private boolean fixtureVisible(BenchClientContext context) {
        return context.level().getBlockEntity(chestPos) != null
                && (!COMPAT_MATRIX || context.level().getBlockEntity(chestPos).getClass()
                        .getName().startsWith("org.cyclops.colossalchests."))
                && context.level().getBlockState(midGlassPos).is(Blocks.BLUE_STAINED_GLASS)
                && context.level().getBlockState(farTintPos).is(
                        COMPAT_MATRIX ? customTintBlock : Blocks.GRASS_BLOCK)
                && (!COMPAT_MATRIX || context.level().getBlockState(customFluidPos).is(customFluidBlock)
                        && !context.level().getFluidState(customFluidPos).isEmpty());
    }

    private void placeFluidBasin(ServerLevel level) {
        placeFixture(level, customFluidPos.below(), Blocks.STONE.defaultBlockState());
        placeFixture(level, customFluidPos.north(), Blocks.STONE.defaultBlockState());
        placeFixture(level, customFluidPos.south(), Blocks.STONE.defaultBlockState());
        placeFixture(level, customFluidPos.east(), Blocks.STONE.defaultBlockState());
        placeFixture(level, customFluidPos.west(), Blocks.STONE.defaultBlockState());
        placeFixture(level, customFluidPos, customFluidBlock.defaultBlockState());
    }

    private static void verifyCompatibilityMods() {
        List<String> missing = REQUIRED_COMPAT_MODS.stream()
                .filter(modId -> !ModList.get().isLoaded(modId)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new AssertionError("Terrain compatibility matrix is missing required mods: " + missing);
        }
    }

    private static Block requiredBlock(String idText) {
        Identifier id = Identifier.parse(idText);
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        if (block == null || block == Blocks.AIR || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new AssertionError("Terrain compatibility block is not registered: " + idText);
        }
        return block;
    }

    private static boolean verifyAccurateTexturesResourcePack(BenchClientContext context) {
        Identifier grassTexture = Identifier.withDefaultNamespace("textures/block/grass_block_top.png");
        var resource = context.minecraft().getResourceManager().getResource(grassTexture)
                .orElseThrow(() -> new AssertionError("Missing active grass texture resource"));
        if (!resource.sourcePackId().contains(RESOURCE_PACK_FILE)) {
            throw new AssertionError("Accurate Textures resource pack is not authoritative for " + grassTexture
                    + ": source=" + resource.sourcePackId());
        }
        try (var input = resource.open()) {
            var image = ImageIO.read(input);
            if (image == null || image.getWidth() != 32 || image.getHeight() != 32) {
                throw new AssertionError("Accurate Textures grass texture must be 32x32, got "
                        + (image == null ? "undecodable" : image.getWidth() + "x" + image.getHeight()));
            }
        } catch (IOException error) {
            throw new AssertionError("Failed to inspect Accurate Textures grass texture", error);
        }
        return true;
    }

    private static long externalTintedCells(TerrainPreviewFrame frame) {
        if (!COMPAT_MATRIX) {
            return 0L;
        }
        return frame.fullDetailSections().stream().flatMap(section -> section.blocks().stream())
                .filter(block -> "biomesoplenty".equals(
                        BuiltInRegistries.BLOCK.getKey(block.state().getBlock()).getNamespace()))
                .filter(block -> !block.tintLayers().isEmpty()).count();
    }

    private static long externalFluidCells(TerrainPreviewFrame frame) {
        if (!COMPAT_MATRIX) {
            return 0L;
        }
        return frame.fullDetailSections().stream().flatMap(section -> section.blocks().stream())
                .filter(block -> "biomesoplenty".equals(
                        BuiltInRegistries.BLOCK.getKey(block.state().getBlock()).getNamespace()))
                .filter(block -> !block.state().getFluidState().isEmpty()).count();
    }

    private long externalBlockEntities(TerrainPreviewFrame frame) {
        if (!COMPAT_MATRIX) {
            return 0L;
        }
        return frame.blockEntities().stream().filter(blockEntity -> blockEntity.worldPos().equals(chestPos))
                .filter(blockEntity -> blockEntity.renderState().getClass().getName()
                        .startsWith("org.cyclops.colossalchests.")).count();
    }

    private static int alignDown(int value, int cellSize) {
        return Math.floorDiv(value, cellSize) * cellSize;
    }

    private static long materialCells(TerrainPreviewFrame frame, int cellSize) {
        return frame.fullDetailSections().stream().flatMap(section -> section.blocks().stream())
                .filter(block -> block.cellSize() == cellSize).count();
    }

    private static boolean completedScreenshot(CompletableFuture<Path> screenshot) {
        if (screenshot == null || !screenshot.isDone()) {
            return false;
        }
        Path path = screenshot.join();
        if (path == null || !Files.isRegularFile(path)) {
            throw new AssertionError("Terrain PIP screenshot was not written: " + path);
        }
        return true;
    }

    private void throwIfFailed() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("Terrain material PIP fixture failed", error);
        }
    }
}
