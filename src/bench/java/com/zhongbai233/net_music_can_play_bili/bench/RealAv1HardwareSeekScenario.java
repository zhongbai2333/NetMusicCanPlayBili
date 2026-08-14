package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureProperties;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class RealAv1HardwareSeekScenario implements BenchClientScenario {
    private static final long LIVE_START_OFFSET_MILLIS = 5_000L;
    private static final long FIXTURE_SEEK_OFFSET_MILLIS = 35_000L;
    private static final long MIN_SEGMENT_ADVANCE_MILLIS = 2_000L;
    private static final int MIN_DISTINCT_PTS_SAMPLES = 12;
    private static final BenchMetricDescriptor MEDIA_MILLIS = new BenchMetricDescriptor(
            "ncpb.real_av1_hardware_seek.media_millis", "milliseconds", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor GENERATION = new BenchMetricDescriptor(
            "ncpb.real_av1_hardware_seek.generation", "generation", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor NATIVE_BYTES = new BenchMetricDescriptor(
            "ncpb.real_av1_hardware_seek.native_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);

    private final boolean frozenFixture;
    private final VideoFeatureProperties.RealMediaLifecycle properties =
            VideoFeatureProperties.realMediaLifecycle();
    private final AtomicReference<Throwable> resolutionFailure = new AtomicReference<>();
    private CompletableFuture<Av1Media> resolution;
    private Av1Media media;
    private BlockPos projectorPos;
    private long[] memoryBaseline;
    private VideoBillboardPreview.BenchUploadResources uploadBaseline;
    private VideoNativeDecoder.NativeMemoryStats nativeBaseline;
    private int closeBaseline;
    private long failedCloseBaseline;
    private int httpBaseline;
    private int ticks;
    private boolean started;
    private boolean initialPlaybackObserved;
    private boolean seekIssued;
    private boolean seekObserved;
    private boolean stopIssued;
    private boolean converged;
    private long initialMediaMillis = -1L;
    private long lastMediaMillis = -1L;
    private long postSeekMediaMillis = -1L;
    private long seekTargetMillis = -1L;
    private long generationBeforeSeek = -1L;
    private int preSeekPtsSamples;
    private int postSeekPtsSamples;
    private String observedBackend = "unknown";
    private FrozenRealAv1RangeServer fixtureServer;

    RealAv1HardwareSeekScenario(boolean frozenFixture) {
        this.frozenFixture = frozenFixture;
    }

    @Override
    public void setup(BenchClientContext context) {
        cleanup();
        projectorPos = context.player().blockPosition().relative(Direction.NORTH, 3).immutable();
        resolution = (frozenFixture
                ? CompletableFuture.supplyAsync(this::resolveFrozenMedia)
                : CompletableFuture.supplyAsync(this::resolveMedia))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        resolutionFailure.compareAndSet(null,
                                RealMediaLifecycleScenario.unwrapCompletion(error));
                    }
                });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        tickClosures();
        throwResolutionFailure();
        if (!context.environment().readiness().ready() || context.frames().sampleCount() < 2
                || resolution == null || !resolution.isDone()) {
            return BenchClientStepResult.CONTINUE;
        }
        if (media == null) {
            media = resolution.join();
        }
        if (!idleForBaseline()) {
            return BenchClientStepResult.CONTINUE;
        }
        memoryBaseline = RealMediaLifecycleScenario.currentOwnedBytesByCategory();
        uploadBaseline = VideoBillboardPreview.benchUploadResources();
        nativeBaseline = VideoNativeDecoder.nativeMemoryStats();
        closeBaseline = VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
        failedCloseBaseline = VideoCloseDiagnostics.global().snapshot(System.nanoTime()).failedConvergences();
        httpBaseline = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests();
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        ticks++;
        tickClosures();
        throwResolutionFailure();
        record(context);
        if (!started) {
            started = true;
            startAt(frozenFixture ? 0L : LIVE_START_OFFSET_MILLIS);
            return BenchClientStepResult.CONTINUE;
        }
        VideoBillboardPreview.pumpPreviewFrame(media.sessionId());
        VideoBillboardPreview.VideoStatus status = VideoBillboardPreview.getStatusForProjector(projectorPos);
        VideoBillboardPreview.VideoSyncStatus sync = VideoBillboardPreview.getSyncStatus(media.sessionId());
        VideoBillboardPreview.BenchDecoderState decoderState =
                VideoBillboardPreview.benchDecoderState(media.sessionId());
        if (VideoBillboardPreview.resourceDiagnostics().failedInstances() > 0) {
            throw new AssertionError("Real AV1 hardware decoder entered terminal failure: " + describe());
        }
        if (status.hasFrame()) {
            validateAv1Hardware(status);
            observePts(sync.mediaMillis());
        }
        long startOffsetMillis = frozenFixture ? 0L : LIVE_START_OFFSET_MILLIS;
        if (!initialPlaybackObserved && status.hasFrame() && sync.mediaMillis() >= startOffsetMillis - 500L) {
            initialPlaybackObserved = true;
            initialMediaMillis = sync.mediaMillis();
            lastMediaMillis = initialMediaMillis;
            observedBackend = status.backend();
        } else if (initialPlaybackObserved && !seekIssued
                && sync.mediaMillis() - initialMediaMillis >= MIN_SEGMENT_ADVANCE_MILLIS
                && preSeekPtsSamples >= MIN_DISTINCT_PTS_SAMPLES) {
            seekTargetMillis = frozenFixture
                    ? FIXTURE_SEEK_OFFSET_MILLIS
                    : Math.min(media.durationMillis() - 5_000L, sync.mediaMillis() + 20_000L);
            if (seekTargetMillis <= sync.mediaMillis() + 10_000L) {
                throw new AssertionError("Resolved media is too short for a forward range seek: " + describe());
            }
            generationBeforeSeek = decoderState.generation();
            seekIssued = true;
            startAt(seekTargetMillis);
        } else if (seekIssued && !seekObserved
                && decoderState.generation() > generationBeforeSeek
                && "ACTIVE".equals(decoderState.restartState())
                && decoderState.decoderStartOffsetMillis() == seekTargetMillis
                && status.hasFrame() && sync.mediaMillis() >= seekTargetMillis - 500L) {
            seekObserved = true;
            postSeekMediaMillis = sync.mediaMillis();
        } else if (seekObserved && !stopIssued
                && sync.mediaMillis() - postSeekMediaMillis >= MIN_SEGMENT_ADVANCE_MILLIS
                && postSeekPtsSamples >= MIN_DISTINCT_PTS_SAMPLES) {
            VideoBillboardPreview.stopIfSession(media.sessionId());
            stopIssued = true;
        } else if (stopIssued && resourcesAtBaseline()) {
            converged = true;
            record(context);
            return BenchClientStepResult.COMPLETE;
        }
        if (stopIssued && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).failedConvergences()
                != failedCloseBaseline) {
            throw new AssertionError("Real AV1 hardware close converged exceptionally: " + describe());
        }
        VideoCloseDiagnostics.Snapshot closeSnapshot = VideoCloseDiagnostics.global().snapshot(
                System.nanoTime());
        if (stopIssued && closeSnapshot.activeOperations() != closeBaseline
                && closeSnapshot.oldestPendingNanos() >= TimeUnit.SECONDS.toNanos(7L)) {
            throw new AssertionError("Real AV1 hardware close did not converge: " + describe());
        }
        if (ticks > 2_400) {
            throw new AssertionError("Real AV1 hardware seek timed out: " + describe());
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (!started || !initialPlaybackObserved || !seekIssued || !seekObserved || !stopIssued || !converged) {
            throw new AssertionError("Real AV1 hardware seek did not complete: " + describe());
        }
        if (isSoftwareBackend(observedBackend) || preSeekPtsSamples < MIN_DISTINCT_PTS_SAMPLES
                || postSeekPtsSamples < MIN_DISTINCT_PTS_SAMPLES) {
            throw new AssertionError("AV1 hardware/PTS evidence is incomplete: " + describe());
        }
        if (frozenFixture && (fixtureServer == null || fixtureServer.fullRequests() < 1
                || fixtureServer.rangeRequests() < 3
                || !fixtureServer.servedRangeStartingAt(FrozenRealAv1RangeServer.SEEK_FRAGMENT_START))) {
            throw new AssertionError("Frozen AV1 fixture did not exercise the exact 35s byte range: "
                    + describe());
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        cleanup();
    }

    private void startAt(long offsetMillis) {
        VideoBillboardPreview.startSyncedCandidates(
                media.candidates(), media.width(), media.height(), media.fps(), media.sessionId(),
                offsetMillis, media.durationMillis(), List.of(projectorPos), projectorPos, true, null);
        VideoBillboardPreview.pumpPreviewFrame(media.sessionId());
    }

    private Av1Media resolveMedia() {
        try {
            BiliApiClient.VideoId id = BiliApiClient.extractVideoId(properties.videoId());
            if (id == null) {
                throw new IOException("invalid Bilibili video id: " + properties.videoId());
            }
            BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(id);
            BiliApiClient.VideoStreamPlan plan = BiliApiClient.getVideoStreamPlan(
                    id, info.cid(), properties.quality());
            BiliVideoStreamResolver.VideoCandidate candidate = plan.candidateOrder().stream()
                    .filter(value -> value.stream().codecId() == BiliApiClient.CODEC_AV1
                            && value.decodePreference() == BiliApiClient.VideoDecodePreference.HARDWARE_REQUIRED)
                    .map(value -> {
                        BiliApiClient.VideoStream stream = value.stream();
                        return new BiliVideoStreamResolver.VideoCandidate(stream.baseUrl(), stream.codecId(),
                                Math.max(1, stream.width()), Math.max(1, stream.height()),
                                BiliVideoStreamResolver.parseFrameRate(stream.frameRate(), 30), stream.quality(),
                                BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED);
                    })
                    .findFirst().orElseThrow(() -> new IOException("playurl plan has no AV1 hardware candidate"));
            return new Av1Media("bench-real-av1-hardware-seek", Math.max(1L, info.duration() * 1_000L),
                    candidate.sourceWidth(), candidate.sourceHeight(), candidate.fps(), List.of(candidate));
        } catch (Exception error) {
            throw new CompletionException(error);
        }
    }

    private Av1Media resolveFrozenMedia() {
        try {
            fixtureServer = FrozenRealAv1RangeServer.start();
            String videoUrl = fixtureServer.videoUrl().toString();
            Fmp4NativeVideoDecoder.registerSegmentBase(videoUrl, 0L, 939L, 992L, 1_491L);
            BiliVideoStreamResolver.VideoCandidate candidate = new BiliVideoStreamResolver.VideoCandidate(
                    videoUrl, BiliApiClient.CODEC_AV1, 682, 360, 25, 16,
                    BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED);
            return new Av1Media("bench-frozen-real-av1-hardware-seek", 40_000L,
                    682, 360, 25, List.of(candidate));
        } catch (IOException error) {
            throw new CompletionException(error);
        }
    }

    private void validateAv1Hardware(VideoBillboardPreview.VideoStatus status) {
        if (status.codecId() != BiliApiClient.CODEC_AV1) {
            throw new AssertionError("Expected AV1 after startup/seek, got " + status);
        }
        if (isSoftwareBackend(status.backend())) {
            throw new AssertionError("AV1 hardware scenario selected a non-hardware backend: " + status);
        }
    }

    private void observePts(long mediaMillis) {
        if (mediaMillis < 0L || stopIssued) {
            return;
        }
        if (lastMediaMillis >= 0L && mediaMillis < lastMediaMillis) {
            throw new AssertionError("Displayed AV1 PTS regressed from " + lastMediaMillis + " to " + mediaMillis
                    + ": " + describe());
        }
        if (mediaMillis != lastMediaMillis) {
            if (seekObserved) {
                postSeekPtsSamples++;
            } else if (!seekIssued) {
                preSeekPtsSamples++;
            }
            lastMediaMillis = mediaMillis;
        }
    }

    private static boolean isSoftwareBackend(String backend) {
        String normalized = backend == null ? "" : backend.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.equals("unknown") || normalized.equals("none")
                || normalized.equals("off") || normalized.startsWith("cpu")
                || normalized.contains("software") || normalized.contains("dav1d");
    }

    private void record(BenchClientContext context) {
        String sessionId = media != null ? media.sessionId() : "";
        VideoBillboardPreview.VideoSyncStatus sync = VideoBillboardPreview.getSyncStatus(sessionId);
        VideoBillboardPreview.BenchDecoderState state = VideoBillboardPreview.benchDecoderState(sessionId);
        VideoNativeDecoder.NativeMemoryStats stats = VideoNativeDecoder.nativeMemoryStats();
        context.metrics().record(MEDIA_MILLIS, Math.max(0L, sync.mediaMillis()));
        context.metrics().record(GENERATION, Math.max(0L, state.generation()));
        context.metrics().record(NATIVE_BYTES, stats.available() ? stats.ffmpegCurrentBytes() : 0L);
    }

    private boolean idleForBaseline() {
        return VideoBillboardPreview.resourceDiagnostics().instances() == 0
                && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                && HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() == 0
                && !VideoBillboardPreview.benchUploadResources().rgbaTexture()
                && !VideoBillboardPreview.benchUploadResources().yuvTextures();
    }

    private boolean resourcesAtBaseline() {
        if (VideoBillboardPreview.getSyncStatus(media.sessionId()).running()
                || VideoBillboardPreview.resourceDiagnostics().instances() != 0
                || VideoBillboardPreview.resourceDiagnostics().activeCloseZombies() != 0
                || VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != closeBaseline
                || VideoCloseDiagnostics.global().snapshot(System.nanoTime()).failedConvergences()
                        != failedCloseBaseline
                || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != httpBaseline
                || !Arrays.equals(memoryBaseline, RealMediaLifecycleScenario.currentOwnedBytesByCategory())) {
            return false;
        }
        VideoBillboardPreview.BenchUploadResources upload = VideoBillboardPreview.benchUploadResources();
        if (upload.rgbaTexture() != uploadBaseline.rgbaTexture()
                || upload.yuvTextures() != uploadBaseline.yuvTextures()
                || upload.textureStagingBytes() != uploadBaseline.textureStagingBytes()
                || upload.gpuPboBytes() != uploadBaseline.gpuPboBytes()) {
            return false;
        }
        VideoNativeDecoder.NativeMemoryStats current = VideoNativeDecoder.nativeMemoryStats();
        return !nativeBaseline.available() || !current.available()
                || current.ffmpegCurrentBytes() == nativeBaseline.ffmpegCurrentBytes()
                && current.d3d11TextureCurrent() == nativeBaseline.d3d11TextureCurrent()
                && current.d3d11SurfaceCurrent() == nativeBaseline.d3d11SurfaceCurrent()
                && current.d3d11LogicalBytesCurrent() == nativeBaseline.d3d11LogicalBytesCurrent();
    }

    private void tickClosures() {
        VideoCloseDiagnostics.tickGlobal();
    }

    private void throwResolutionFailure() {
        Throwable error = resolutionFailure.get();
        if (error != null) {
            throw new AssertionError("Failed to resolve real AV1 hardware media", error);
        }
    }

    private void cleanup() {
        if (media != null) {
            VideoBillboardPreview.stopIfSession(media.sessionId());
        }
        VideoBillboardPreview.stop();
        VideoBillboardPreview.releaseBenchUploadResources();
        if (fixtureServer != null) {
            fixtureServer.close();
            fixtureServer = null;
        }
    }

    private String describe() {
        String sessionId = media != null ? media.sessionId() : "";
        return "started=" + started + " initial=" + initialPlaybackObserved + " seekIssued=" + seekIssued
                + " seekObserved=" + seekObserved + " stop=" + stopIssued + " converged=" + converged
                + " backend=" + observedBackend + " media=" + lastMediaMillis + " target=" + seekTargetMillis
                + " samples=" + preSeekPtsSamples + "/" + postSeekPtsSamples + " status="
                + VideoBillboardPreview.getStatusForProjector(projectorPos) + " sync="
                + VideoBillboardPreview.getSyncStatus(sessionId) + " decoder="
                + VideoBillboardPreview.benchDecoderState(sessionId) + " resources="
                + VideoBillboardPreview.resourceDiagnostics() + " upload="
                + VideoBillboardPreview.benchUploadResources() + " native="
                + VideoNativeDecoder.nativeMemoryStats() + " closes="
                + VideoCloseDiagnostics.global().snapshot(System.nanoTime()) + " activeCloses="
                + VideoCloseDiagnostics.global().activeDescriptions(System.nanoTime()) + " http="
                + HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()) + " memory="
                + Arrays.toString(RealMediaLifecycleScenario.currentOwnedBytesByCategory())
                + " fixture=" + (fixtureServer == null ? "none"
                        : fixtureServer.fullRequests() + "/" + fixtureServer.rangeRequests());
    }

    private record Av1Media(String sessionId, long durationMillis, int width, int height, int fps,
            List<BiliVideoStreamResolver.VideoCandidate> candidates) {
    }
}
