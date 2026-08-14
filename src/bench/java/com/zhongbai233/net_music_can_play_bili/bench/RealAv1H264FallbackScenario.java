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
import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

final class RealAv1H264FallbackScenario implements BenchClientScenario {
    private static final long START_OFFSET_MILLIS = 5_000L;
    private static final String REJECTED_AV1_URL =
            "http://127.0.0.1:1/ncpb-bench-av1-startup-failure.m4s";
    private static final BenchMetricDescriptor FALLBACK_CODEC = new BenchMetricDescriptor(
            "ncpb.real_av1_fallback.codec_id", "codec", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor MEDIA_MILLIS = new BenchMetricDescriptor(
            "ncpb.real_av1_fallback.media_millis", "milliseconds", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor OWNED_BYTES = new BenchMetricDescriptor(
            "ncpb.real_av1_fallback.owned_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor NATIVE_BYTES = new BenchMetricDescriptor(
            "ncpb.real_av1_fallback.native_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);

    private final VideoFeatureProperties.RealMediaLifecycle properties =
            VideoFeatureProperties.realMediaLifecycle();
    private final AtomicReference<Throwable> resolutionFailure = new AtomicReference<>();
    private CompletableFuture<FallbackMedia> resolution;
    private FallbackMedia media;
    private BlockPos projectorPos;
    private long[] memoryBaseline;
    private VideoBillboardPreview.BenchUploadResources uploadBaseline;
    private VideoNativeDecoder.NativeMemoryStats nativeBaseline;
    private int closeBaseline;
    private int httpBaseline;
    private int ticks;
    private boolean started;
    private boolean fallbackObserved;
    private boolean stopIssued;
    private boolean converged;
    private String observedBackend = "unknown";
    private String observedReason = "";
    private long observedMediaMillis = -1L;

    @Override
    public void setup(BenchClientContext context) {
        cleanup();
        projectorPos = context.player().blockPosition().relative(Direction.NORTH, 2).immutable();
        resolution = CompletableFuture.supplyAsync(this::resolveMedia)
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
            VideoBillboardPreview.startSyncedCandidates(
                    media.candidates(), media.width(), media.height(), media.fps(), media.sessionId(),
                    START_OFFSET_MILLIS, media.durationMillis(), List.of(projectorPos), projectorPos,
                    true, null);
            // The benchmark does not place a projector block. Keep a GUI
            // consumer attached so the real candidate loop and upload pump
            // remain active while the injected backend rejection settles.
            VideoBillboardPreview.pumpPreviewFrame(media.sessionId());
            return BenchClientStepResult.CONTINUE;
        }
        VideoBillboardPreview.pumpPreviewFrame(media.sessionId());
        if (!fallbackObserved) {
            VideoBillboardPreview.VideoStatus status = VideoBillboardPreview.getStatusForProjector(projectorPos);
            VideoBillboardPreview.VideoSyncStatus sync = VideoBillboardPreview.getSyncStatus(media.sessionId());
            if (status.hasFrame()) {
                if (status.codecId() != 7) {
                    throw new AssertionError("Expected real AV1 hardware rejection to select H.264, got "
                            + status);
                }
                if (status.fallbackReason().isBlank()) {
                    throw new AssertionError("AV1 to H.264 fallback did not expose a stable reason: " + status);
                }
                if (!sync.running() || sync.mediaMillis() < START_OFFSET_MILLIS - 1_000L) {
                    throw new AssertionError("Fallback reset or detached the session timeline: " + sync);
                }
                fallbackObserved = true;
                observedBackend = status.backend();
                observedReason = status.fallbackReason();
                observedMediaMillis = sync.mediaMillis();
                VideoBillboardPreview.stopIfSession(media.sessionId());
                stopIssued = true;
            }
        } else if (resourcesAtBaseline()) {
            converged = true;
            record(context);
            return BenchClientStepResult.COMPLETE;
        }
        if (ticks > 1_200) {
            throw new AssertionError("Real AV1 fallback timed out: " + describe());
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (!started || !fallbackObserved || !stopIssued || !converged) {
            throw new AssertionError("Real AV1 fallback did not complete: " + describe());
        }
        if (observedBackend.equalsIgnoreCase("unknown") || observedMediaMillis < START_OFFSET_MILLIS - 1_000L) {
            throw new AssertionError("Fallback backend/timeline evidence is incomplete: " + describe());
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        cleanup();
    }

    private FallbackMedia resolveMedia() {
        try {
            BiliApiClient.VideoId id = BiliApiClient.extractVideoId(properties.videoId());
            if (id == null) {
                throw new IOException("invalid Bilibili video id: " + properties.videoId());
            }
            BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(id);
            BiliApiClient.VideoStreamPlan plan = BiliApiClient.getVideoStreamPlan(
                    id, info.cid(), properties.quality());
            List<BiliVideoStreamResolver.VideoCandidate> candidates = plan.candidateOrder().stream()
                    .map(candidate -> {
                        BiliApiClient.VideoStream stream = candidate.stream();
                        BiliVideoStreamResolver.DecodeMode mode = switch (candidate.decodePreference()) {
                            case HARDWARE_REQUIRED -> BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED;
                            case AUTO -> BiliVideoStreamResolver.DecodeMode.AUTO;
                            case SOFTWARE_ONLY -> BiliVideoStreamResolver.DecodeMode.SOFTWARE_ONLY;
                        };
                        return new BiliVideoStreamResolver.VideoCandidate(stream.baseUrl(), stream.codecId(),
                                Math.max(1, stream.width()), Math.max(1, stream.height()),
                                BiliVideoStreamResolver.parseFrameRate(stream.frameRate(), 30),
                                stream.quality(), mode);
                    })
                    .toList();
            BiliVideoStreamResolver.VideoCandidate av1 = candidates.stream()
                    .filter(candidate -> candidate.codecId() == 13
                            && candidate.decodeMode() == BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED)
                    .findFirst().orElseThrow(() -> new IOException("playurl plan has no AV1 hardware candidate"));
            BiliVideoStreamResolver.VideoCandidate h264 = candidates.stream()
                    .filter(candidate -> candidate.codecId() == 7)
                    .findFirst().orElseThrow(() -> new IOException("playurl plan has no H.264 fallback"));
            // Keep the real candidate metadata and production codec-13
            // native construction, but make its transport fail immediately
            // and deterministically on every host. This validates the
            // physical close barrier plus the real H.264 fallback without
            // assuming that the current machine lacks AV1 hardware.
            BiliVideoStreamResolver.VideoCandidate rejectedAv1 =
                    new BiliVideoStreamResolver.VideoCandidate(
                            REJECTED_AV1_URL, av1.codecId(), av1.sourceWidth(), av1.sourceHeight(),
                            av1.fps(), av1.quality(), av1.decodeMode());
            return new FallbackMedia("bench-real-av1-h264-fallback", Math.max(1L, info.duration() * 1_000L),
                    Math.max(1, av1.sourceWidth()), Math.max(1, av1.sourceHeight()),
                    Math.max(1, av1.fps()), List.of(rejectedAv1, h264));
        } catch (Exception error) {
            throw new CompletionException(error);
        }
    }

    private void record(BenchClientContext context) {
        VideoBillboardPreview.VideoStatus status = VideoBillboardPreview.getStatusForProjector(projectorPos);
        VideoNativeDecoder.NativeMemoryStats stats = VideoNativeDecoder.nativeMemoryStats();
        context.metrics().record(FALLBACK_CODEC, status.codecId());
        long currentMediaMillis = VideoBillboardPreview.getSyncStatus(
                media != null ? media.sessionId() : "").mediaMillis();
        context.metrics().record(MEDIA_MILLIS,
                Math.max(0L, Math.max(observedMediaMillis, currentMediaMillis)));
        context.metrics().record(OWNED_BYTES, RealMediaLifecycleScenario.currentOwnedBytes());
        context.metrics().record(NATIVE_BYTES, stats.available() ? stats.ffmpegCurrentBytes() : 0L);
    }

    private boolean idleForBaseline() {
        return VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                && HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() == 0
                && !VideoBillboardPreview.benchUploadResources().rgbaTexture()
                && !VideoBillboardPreview.benchUploadResources().yuvTextures();
    }

    private boolean resourcesAtBaseline() {
        if (VideoBillboardPreview.getSyncStatus(media.sessionId()).running()
                || VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != closeBaseline
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
            throw new AssertionError("Failed to resolve real AV1/H.264 fallback media", error);
        }
    }

    private void cleanup() {
        if (media != null) {
            VideoBillboardPreview.stopIfSession(media.sessionId());
        }
        VideoBillboardPreview.stop();
        VideoBillboardPreview.releaseBenchUploadResources();
    }

    private String describe() {
        VideoNativeDecoder.NativeMemoryStats nativeStats = VideoNativeDecoder.nativeMemoryStats();
        return "started=" + started + " fallback=" + fallbackObserved + " stop=" + stopIssued
                + " converged=" + converged + " backend=" + observedBackend + " reason=" + observedReason
                + " mediaMillis=" + observedMediaMillis + " status="
                + VideoBillboardPreview.getStatusForProjector(projectorPos) + " upload="
                + VideoBillboardPreview.benchUploadResources() + " native=" + nativeStats
                + " closes=" + VideoCloseDiagnostics.global().snapshot(System.nanoTime())
                + " http=" + HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime())
                + " memory=" + Arrays.toString(RealMediaLifecycleScenario.currentOwnedBytesByCategory());
    }

private record FallbackMedia(String sessionId, long durationMillis, int width, int height, int fps,
            List<BiliVideoStreamResolver.VideoCandidate> candidates) {
    }
}
