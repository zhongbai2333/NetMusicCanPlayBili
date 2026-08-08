package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchApiVersion;
import com.zhongbai233.bench.api.BenchCompatibility;
import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.ScenarioDescriptor;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientProvider;
import com.zhongbai233.bench.api.neoforge.client.BenchClientRegistrar;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.bench.api.neoforge.client.BenchGuiSession;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.DeterministicVideoUploadWorkload;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainHardRangeBounds;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewFrame;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;

/** Test-only integrated-client workloads. This class must never enter the production jar. */
public final class NetMusicBenchProvider implements BenchClientProvider {
    public NetMusicBenchProvider() {
    }

    @Override
    public String id() {
        return "ncpb";
    }

    @Override
    public BenchCompatibility compatibility() {
        return BenchApiVersion.currentCompatibility();
    }

    @Override
    public void registerClient(BenchClientRegistrar registrar) {
        registrar.register(new ScenarioDescriptor(
                "ncpb.console-consumer-lifecycle",
                "100 rounds of shared control-console video consumer attach/detach",
                Set.of("client", "console", "lifecycle", "resources"), Duration.ofSeconds(20)),
                ignored -> new ConsoleConsumerLifecycleScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.editor-gui-lifecycle", "30 real editor Screen open/render/snapshot/close rounds",
                Set.of("client", "gui", "editor", "lifecycle"), Duration.ofSeconds(30)),
                ignored -> new EditorGuiLifecycleScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.terrain-lod-roundtrip", "Terrain NEAR to FAR to NEAR convergence",
                Set.of("client", "terrain", "lod", "resources"), Duration.ofSeconds(30)),
                ignored -> new TerrainLodRoundTripScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.media-resource-convergence", "Video, OpenAL and owned-memory idle convergence",
                Set.of("client", "media", "resources", "close"), Duration.ofSeconds(10)),
                ignored -> new MediaResourceConvergenceScenario());
        registrar.register(new ScenarioDescriptor(
                "ncpb.deterministic-video-upload",
                "Deterministic RGBA, YUV420P and NV12/PBO upload and release convergence",
                Set.of("client", "media", "gpu", "upload", "resources"), Duration.ofSeconds(20)),
                ignored -> new DeterministicVideoUploadScenario());
    }

    private static final class DeterministicVideoUploadScenario implements BenchClientScenario {
        private static final int WIDTH = 640;
        private static final int HEIGHT = 360;
        private static final int FRAMES_PER_FORMAT = 30;
        private static final VideoBillboardPreview.BenchUploadFormat[] FORMATS =
                VideoBillboardPreview.BenchUploadFormat.values();
        private static final BenchMetricDescriptor UPLOAD_LATENCY = new BenchMetricDescriptor(
                "ncpb.video.upload_latency", "ms", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor UPLOAD_BYTES = new BenchMetricDescriptor(
                "ncpb.video.upload_bytes", "bytes", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor UPLOAD_P95 = new BenchMetricDescriptor(
                "ncpb.video.upload_p95", "ms", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor UPLOAD_P99 = new BenchMetricDescriptor(
                "ncpb.video.upload_p99", "ms", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor STAGING_BYTES = new BenchMetricDescriptor(
                "ncpb.video.texture_staging_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor PBO_BYTES = new BenchMetricDescriptor(
                "ncpb.video.gpu_pbo_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);

        private final long[][] uploadNanos = new long[FORMATS.length][FRAMES_PER_FORMAT];
        private long baselineStaging;
        private long baselinePbo;
        private long peakStagingDelta;
        private long peakPboDelta;
        private int formatIndex;
        private int frameIndex;
        private boolean released;

        @Override
        public void setup(BenchClientContext context) {
            ModernTurntableVideoClient.clear();
            ConsoleConsumerLifecycleScenario.requireClean("deterministic upload setup");
            VideoBillboardPreview.releaseBenchUploadResources();
            var resources = VideoBillboardPreview.benchUploadResources();
            baselineStaging = resources.textureStagingBytes();
            baselinePbo = resources.gpuPboBytes();
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            return context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            upload(VideoBillboardPreview.BenchUploadFormat.RGBA, -1);
            VideoBillboardPreview.releaseBenchUploadResources();
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            VideoBillboardPreview.BenchUploadFormat format = FORMATS[formatIndex];
            byte[] frame = DeterministicVideoUploadWorkload.frame(format, WIDTH, HEIGHT, frameIndex);
            long elapsedNanos = VideoBillboardPreview.uploadFrameOnClientThreadForBench(
                    format, frame, WIDTH, HEIGHT);
            if (elapsedNanos < 0L) {
                throw new AssertionError("GPU upload failed: format=" + format + ", frame=" + frameIndex);
            }
            uploadNanos[formatIndex][frameIndex] = elapsedNanos;
            context.metrics().record(UPLOAD_LATENCY, elapsedNanos / 1_000_000.0D);
            context.metrics().record(UPLOAD_BYTES, frame.length);
            var resources = VideoBillboardPreview.benchUploadResources();
            long stagingDelta = Math.max(0L, resources.textureStagingBytes() - baselineStaging);
            long pboDelta = Math.max(0L, resources.gpuPboBytes() - baselinePbo);
            peakStagingDelta = Math.max(peakStagingDelta, stagingDelta);
            peakPboDelta = Math.max(peakPboDelta, pboDelta);
            context.metrics().record(STAGING_BYTES, stagingDelta);
            context.metrics().record(PBO_BYTES, pboDelta);

            frameIndex++;
            if (frameIndex < FRAMES_PER_FORMAT) {
                return BenchClientStepResult.CONTINUE;
            }
            recordPercentiles(context, uploadNanos[formatIndex]);
            frameIndex = 0;
            formatIndex++;
            if (formatIndex < FORMATS.length) {
                return BenchClientStepResult.CONTINUE;
            }
            VideoBillboardPreview.releaseBenchUploadResources();
            released = true;
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (!released || formatIndex != FORMATS.length) {
                throw new AssertionError("Deterministic upload workload did not finish all formats");
            }
            if (peakStagingDelta <= 0L) {
                throw new AssertionError("YUV upload did not allocate tracked texture staging memory");
            }
            if (peakPboDelta <= 0L) {
                throw new AssertionError("NV12 upload did not allocate tracked PBO memory");
            }
            var resources = VideoBillboardPreview.benchUploadResources();
            if (resources.rgbaTexture() || resources.yuvTextures()
                    || resources.textureStagingBytes() != baselineStaging
                    || resources.gpuPboBytes() != baselinePbo) {
                throw new AssertionError("GPU upload resources did not return to baseline: " + resources);
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            VideoBillboardPreview.releaseBenchUploadResources();
        }

        private static long upload(VideoBillboardPreview.BenchUploadFormat format, int frameIndex) {
            byte[] frame = DeterministicVideoUploadWorkload.frame(format, WIDTH, HEIGHT, frameIndex);
            return VideoBillboardPreview.uploadFrameOnClientThreadForBench(format, frame, WIDTH, HEIGHT);
        }

        private static void recordPercentiles(BenchClientContext context, long[] values) {
            long[] sorted = values.clone();
            Arrays.sort(sorted);
            context.metrics().record(UPLOAD_P95, percentile(sorted, 0.95D) / 1_000_000.0D);
            context.metrics().record(UPLOAD_P99, percentile(sorted, 0.99D) / 1_000_000.0D);
        }

        private static long percentile(long[] sorted, double quantile) {
            int index = Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * quantile) - 1);
            return sorted[Math.max(0, index)];
        }
    }

    private static final class EditorGuiLifecycleScenario implements BenchClientScenario {
        private static final int ROUNDS = 30;
        private static final BenchMetricDescriptor WIDGETS = new BenchMetricDescriptor(
                "ncpb.gui.widgets", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor OPEN_ROUNDS = new BenchMetricDescriptor(
                "ncpb.gui.open_rounds", "count", MetricDirection.NEUTRAL);
        private BenchGuiSession gui;
        private int round;

        @Override
        public void setup(BenchClientContext context) {
            context.minecraft().setScreen(new HolographicScreenConfigTestScreen());
            gui = context.automation().beginGuiSession(HolographicScreenConfigTestScreen.class);
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            return context.frames().sampleCount() >= 2
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            if (!(context.minecraft().screen instanceof HolographicScreenConfigTestScreen)) {
                throw new AssertionError("Editor Screen did not remain open for a rendered tick");
            }
            int widgets = gui.snapshot().flattened().size();
            if (widgets < 4) {
                throw new AssertionError("Editor interaction tree unexpectedly small: " + widgets);
            }
            context.metrics().record(WIDGETS, widgets);
            context.metrics().record(OPEN_ROUNDS, ++round);
            if (round >= ROUNDS) {
                context.minecraft().setScreen(null);
                return BenchClientStepResult.COMPLETE;
            }
            context.minecraft().setScreen(new HolographicScreenConfigTestScreen());
            return BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (round != ROUNDS || context.minecraft().screen != null || !gui.active()) {
                throw new AssertionError("GUI lifecycle did not converge: rounds=" + round);
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            context.minecraft().setScreen(null);
            if (gui != null) gui.close();
        }
    }

    private static final class TerrainLodRoundTripScenario implements BenchClientScenario {
        private static final BenchMetricDescriptor NEAR_SECTIONS = new BenchMetricDescriptor(
                "ncpb.terrain.near_sections", "count", MetricDirection.NEUTRAL);
        private static final BenchMetricDescriptor OVERVIEW_CELLS = new BenchMetricDescriptor(
                "ncpb.terrain.overview_cells", "count", MetricDirection.LOWER_IS_BETTER);
        private BlockPos origin;
        private com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds bounds;
        private int phase;
        private long generation;
        private int sampledBeforeReopen;

        @Override
        public void setup(BenchClientContext context) {
            origin = context.player().blockPosition();
            bounds = TerrainHardRangeBounds.around(origin.getX(), origin.getY(), origin.getZ(),
                    24.0D, 16.0D, 24.0D, context.level().getMinY(), context.level().getMaxY());
            TerrainPreviewManager.clear();
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            return context.environment().readiness().ready()
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            Vector3d fixedCore = new Vector3d(0.5D, 0.5D, 0.5D);
            TerrainPreviewManager.update(context.level(), origin, bounds, fixedCore);
            TerrainPreviewFrame frame = TerrainPreviewManager.frame();
            if (generation == 0L) {
                generation = frame.generation();
            } else if (frame.generation() != generation) {
                throw new AssertionError("Fixed terrain core unexpectedly rebuilt: "
                        + generation + " -> " + frame.generation());
            }
            context.metrics().record(NEAR_SECTIONS, frame.fullDetailSections().size());
            context.metrics().record(OVERVIEW_CELLS, loadedOverviewCells(frame));
            boolean converged = !frame.fullDetailSectionKeys().isEmpty()
                    && loadedOverviewCells(frame) > 0;
            if (!converged) return BenchClientStepResult.CONTINUE;
            phase++;
            if (phase == 1) {
                sampledBeforeReopen = frame.sampledSections();
                TerrainPreviewManager.close(origin);
                TerrainPreviewManager.update(context.level(), origin, bounds, fixedCore);
                TerrainPreviewFrame reopened = TerrainPreviewManager.frame();
                if (reopened.generation() != generation
                        || reopened.sampledSections() < sampledBeforeReopen
                        || reopened.fullDetailSectionKeys().isEmpty()
                        || loadedOverviewCells(reopened) == 0) {
                    throw new AssertionError("Parked terrain cache was not restored immediately: sampled="
                            + sampledBeforeReopen + " -> " + reopened.sampledSections());
                }
            }
            return phase >= 3 ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            TerrainPreviewFrame frame = TerrainPreviewManager.frame();
            if (phase != 3 || frame.fullDetailSectionKeys().isEmpty() || loadedOverviewCells(frame) == 0) {
                throw new AssertionError("Fixed terrain core did not converge: phase=" + phase);
            }
        }

        @Override
        public void teardown(BenchClientContext context) {
            TerrainPreviewManager.clear();
        }

        private static long loadedOverviewCells(TerrainPreviewFrame frame) {
            return frame.overviewCells().stream().filter(cell -> cell.material()
                    != com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample.RenderCategory.UNKNOWN)
                    .count();
        }
    }

    private static final class MediaResourceConvergenceScenario implements BenchClientScenario {
        private static final int MEASURE_TICKS = 40;
        private static final BenchMetricDescriptor VIDEO_CLOSE_ACTIVE = new BenchMetricDescriptor(
                "ncpb.video.close_active", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor AUDIO_CLOSE_ACTIVE = new BenchMetricDescriptor(
                "ncpb.openal.close_active", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor AUDIO_PENDING_BATCHES = new BenchMetricDescriptor(
                "ncpb.openal.pending_delete_batches", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor HTTP_ACTIVE = new BenchMetricDescriptor(
            "ncpb.http.active_requests", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor HTTP_CANCELS = new BenchMetricDescriptor(
            "ncpb.http.cancel_requests", "count", MetricDirection.NEUTRAL);
        private int ticks;

        @Override public void setup(BenchClientContext context) { ModernTurntableVideoClient.clear(); }
        @Override public BenchClientStepResult stabilize(BenchClientContext context) {
            return context.frames().sampleCount() >= 2 ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }
        @Override public BenchClientStepResult warmup(BenchClientContext context) { return BenchClientStepResult.COMPLETE; }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            var video = VideoCloseDiagnostics.global().snapshot(System.nanoTime());
            var audio = AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime());
            context.metrics().record(VIDEO_CLOSE_ACTIVE, video.activeOperations());
            context.metrics().record(AUDIO_CLOSE_ACTIVE, audio.activeOperations());
            context.metrics().record(AUDIO_PENDING_BATCHES, OpenALSpatialAudio.pendingNativeDeleteBatches());
            var http = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
            context.metrics().record(HTTP_ACTIVE, http.activeRequests());
            context.metrics().record(HTTP_CANCELS, http.cancelRequests());
            return ++ticks >= MEASURE_TICKS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            ConsoleConsumerLifecycleScenario.requireClean("resource convergence");
            if (VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != 0
                    || AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != 0
                    || OpenALSpatialAudio.pendingNativeDeleteBatches() != 0
                    || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != 0) {
                throw new AssertionError("Native close operations did not converge");
            }
            for (MemoryResourceTracker.Category category : MemoryResourceTracker.Category.values()) {
                if (MemoryResourceTracker.usage(category).currentBytes() != 0L) {
                    throw new AssertionError("Owned memory did not converge: " + category);
                }
            }
        }
    }

    private static final class ConsoleConsumerLifecycleScenario implements BenchClientScenario {
        private static final int ROUNDS = 100;
        private static final BlockPos SOURCE = new BlockPos(0, 64, 0);
        private static final BenchMetricDescriptor CONSUMERS = new BenchMetricDescriptor(
                "ncpb.console.consumers", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor VIDEO_INSTANCES = new BenchMetricDescriptor(
                "ncpb.video.instances", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor PENDING_REQUESTS = new BenchMetricDescriptor(
                "ncpb.video.pending_requests", "count", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor MEMORY_BYTES = new BenchMetricDescriptor(
                "ncpb.memory.current_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);

        private int round;

        @Override
        public void setup(BenchClientContext context) {
            ModernTurntableVideoClient.clear();
            requireClean("setup");
        }

        @Override
        public BenchClientStepResult stabilize(BenchClientContext context) {
            return context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public BenchClientStepResult warmup(BenchClientContext context) {
            exerciseRound(-1);
            requireClean("warmup");
            return BenchClientStepResult.COMPLETE;
        }

        @Override
        public BenchClientStepResult measure(BenchClientContext context) {
            exerciseRound(round);
            record(context);
            return ++round >= ROUNDS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchClientContext context) {
            if (round != ROUNDS) {
                throw new AssertionError("Expected " + ROUNDS + " lifecycle rounds, got " + round);
            }
            requireClean("verify");
        }

        @Override
        public void teardown(BenchClientContext context) {
            ModernTurntableVideoClient.clear();
        }

        private static void exerciseRound(int index) {
            BlockPos first = new BlockPos(index * 2 + 1, 64, 1);
            BlockPos second = new BlockPos(index * 2 + 2, 64, 1);
            ModernTurntableVideoClient.registerControlConsoleConsumer(SOURCE, first, 116);
            ModernTurntableVideoClient.registerControlConsoleConsumer(SOURCE, second, 116);
            assertConsumers(2, "both consumers attached");
            ModernTurntableVideoClient.unregisterControlConsoleConsumer(first);
            assertConsumers(1, "shared consumer remains");
            ModernTurntableVideoClient.unregisterControlConsoleConsumer(second);
            assertConsumers(0, "last consumer detached");
        }

        private static void record(BenchClientContext context) {
            ModernTurntableVideoClient.VideoLifecycleDiagnostics lifecycle =
                    ModernTurntableVideoClient.videoLifecycleDiagnostics();
            context.metrics().record(CONSUMERS, lifecycle.controlConsoleConsumers());
            context.metrics().record(VIDEO_INSTANCES, lifecycle.resources().instances());
            context.metrics().record(PENDING_REQUESTS, lifecycle.pendingRequests());
            for (MemoryResourceTracker.Category category : MemoryResourceTracker.Category.values()) {
                context.metrics().record(MEMORY_BYTES, MemoryResourceTracker.usage(category).currentBytes());
            }
        }

        private static void requireClean(String phase) {
            ModernTurntableVideoClient.VideoLifecycleDiagnostics lifecycle =
                    ModernTurntableVideoClient.videoLifecycleDiagnostics();
            if (lifecycle.controlConsoleConsumers() != 0 || lifecycle.activeRequests() != 0
                    || lifecycle.pendingRequests() != 0 || lifecycle.resources().instances() != 0
                    || lifecycle.resources().pendingLoading() != 0 || lifecycle.resources().pendingFailure() != 0) {
                throw new AssertionError("Video lifecycle not clean during " + phase + ": " + lifecycle);
            }
        }

        private static void assertConsumers(int expected, String phase) {
            int actual = ModernTurntableVideoClient.videoLifecycleDiagnostics().controlConsoleConsumers();
            if (actual != expected) {
                throw new AssertionError(phase + ": expected " + expected + " consumers, got " + actual);
            }
        }
    }
}