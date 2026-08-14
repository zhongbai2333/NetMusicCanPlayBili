package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.MULTI_CLIENT_CONSOLE_POS;
import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.MULTI_CLIENT_REAL_MEDIA_LOADED;
import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.MULTI_CLIENT_REAL_MEDIA_CONVERGED;
import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.MULTI_CLIENT_REAL_MEDIA_OWNED_BYTES;
import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.MULTI_CLIENT_REAL_MEDIA_IRIS;
import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requirePcmQuality;

import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureProperties;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class MultiClientRealMediaScenario implements BenchClientScenario {
    private static final int LOADED_HOLD_TICKS = 40;
    private static final int SURVIVOR_HOLD_TICKS = 60;
    private final int clientIndex = Integer.getInteger("modBench.paired.clientIndex", -1);
    private final int clientCount = Integer.getInteger("modBench.paired.clientCount", -1);
    private final boolean irisExpected = Boolean.getBoolean("ncpb.terrain.compat_matrix");
    private final VideoFeatureProperties.RealMediaLifecycle properties =
            VideoFeatureProperties.realMediaLifecycle();
    private final AtomicReference<Throwable> resolutionFailure = new AtomicReference<>();
    private final AtomicBoolean stopIssued = new AtomicBoolean();
    private CompletableFuture<PairedResolvedMedia> resolution;
    private PairedResolvedMedia media;
    private UUID audioOwner;
    private PlaybackSessionId mediaSession;
    private RealMediaLifecycleScenario.RealVideoStage video;
    private RealMediaLifecycleScenario.RealAudioStage audio;
    private long[] memoryBaseline;
    private VideoBillboardPreview.BenchUploadResources uploadBaseline;
    private StereoOpenALHandler.LifecycleSnapshot stereoBaseline;
    private OpenALTappedAudioInputStream.LifecycleSnapshot tapBaseline;
    private int videoCloseBaseline;
    private int audioCloseBaseline;
    private int pendingNativeDeleteBaseline;
    private int loadedTicks;
    private int survivorTicks;
    private int measureTicks;
    private boolean baselineCaptured;
    private boolean mediaStarted;
    private boolean mediaLoaded;
    private boolean observedBothPlayers;
    private boolean observedPeerExit;
    private boolean survivorStayedLoaded;
    private boolean converged;
    private boolean irisObserved;

    @Override
    public void setup(BenchClientContext context) {
        if (clientCount != 2 || clientIndex < 0 || clientIndex >= clientCount) {
            throw new AssertionError("Invalid paired real-media role " + clientIndex + '/' + clientCount);
        }
        cleanupGlobalResources();
        audioOwner = context.player().getUUID();
        ClientAudioOutputRegistry.setOwnerVolume(audioOwner, 1.0F);
        mediaSession = PlaybackSessionId.of("bench-paired-real-media-" + clientIndex);
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
        tickResourceClosures(context);
        throwResolutionFailure();
        if (!context.environment().readiness().ready() || context.frames().sampleCount() < 2
                || !(context.level().getBlockEntity(MULTI_CLIENT_CONSOLE_POS)
                        instanceof ControlConsoleBlockEntity)
                || context.player().position().distanceTo(Vec3.atCenterOf(MULTI_CLIENT_CONSOLE_POS)) > 8.0D
                || !ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS).registered()
                || resolution == null || !resolution.isDone()) {
            return BenchClientStepResult.CONTINUE;
        }
        if (media == null) {
            media = resolution.join();
        }
        if (!idleForBaseline()) {
            return BenchClientStepResult.CONTINUE;
        }
        captureBaseline();
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        tickResourceClosures(context);
        throwResolutionFailure();
        if (!mediaStarted) {
            startMedia();
        }
        pollLoadedMedia(context);
        if (!mediaLoaded) {
            return BenchClientStepResult.CONTINUE;
        }
        observedBothPlayers |= onlinePlayers(context) == 2;
        return observedBothPlayers && ++loadedTicks >= LOADED_HOLD_TICKS
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        tickResourceClosures(context);
        throwStageFailures();
        recordMetrics(context);
        measureTicks++;
        int online = onlinePlayers(context);
        observedBothPlayers |= online == 2;
        var lease = ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS);
        if (clientIndex == 0) {
            if (!stopIssued.get() && (!lease.active() || !lease.leasePresent() || !mediaStillLoaded())) {
                throw new AssertionError("Departing real-media client lost its live resources before exit: "
                        + describeResources(context));
            }
            if (observedBothPlayers && measureTicks >= 80 && !stopIssued.get()) {
                stopMedia();
            }
            if (stopIssued.get() && resourcesAtBaseline()) {
                converged = true;
                recordMetrics(context);
                return BenchClientStepResult.COMPLETE;
            }
            return BenchClientStepResult.CONTINUE;
        }

        if (!observedPeerExit && observedBothPlayers && online == 1) {
            observedPeerExit = true;
        }
        if (observedPeerExit && !stopIssued.get()) {
            if (!lease.active() || !lease.leasePresent() || !mediaStillLoaded()) {
                throw new AssertionError("Surviving client lost media or its lease after peer exit: "
                        + describeResources(context));
            }
            survivorStayedLoaded = true;
            if (++survivorTicks >= SURVIVOR_HOLD_TICKS) {
                stopMedia();
            }
        }
        if (stopIssued.get() && resourcesAtBaseline()) {
            converged = true;
            recordMetrics(context);
            return BenchClientStepResult.COMPLETE;
        }
        if (measureTicks > properties.cycleTimeoutTicks() * 2) {
            throw new AssertionError("Paired real-media lifecycle stalled: " + describeResources(context));
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (!baselineCaptured || !mediaLoaded || !observedBothPlayers
                || irisExpected && !irisObserved) {
            throw new AssertionError("Paired real-media client did not reach its loaded gate: "
                    + describeResources(context));
        }
        if (!converged || video == null || !video.terminatedNormally()
                || audio == null || !audio.streamClosed()
                || clientIndex == 1 && (!observedPeerExit || !survivorStayedLoaded)) {
            throw new AssertionError("Surviving real-media client did not converge independently: "
                    + describeResources(context));
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        stopMedia();
        cleanupGlobalResources();
    }

    private PairedResolvedMedia resolveMedia() {
        try {
            BiliApiClient.VideoId videoId = BiliApiClient.extractVideoId(properties.videoId());
            if (videoId == null) {
                throw new IOException("invalid Bilibili video id: " + properties.videoId());
            }
            BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(videoId);
            BiliApiClient.VideoStreamPlan plan = BiliApiClient.getVideoStreamPlan(
                    videoId, info.cid(), properties.quality());
            BiliApiClient.VideoStream videoStream = !plan.h264Candidates().isEmpty()
                    ? plan.h264Candidates().getFirst() : plan.preferred();
            String audioUrl = BiliApiClient.getBestAudioUrl(videoId, info.cid(), false);
            if (videoStream.baseUrl().isBlank() || audioUrl == null || audioUrl.isBlank()) {
                throw new IOException("Bilibili playurl returned an empty paired media URL");
            }
            return new PairedResolvedMedia(Math.max(1L, info.duration() * 1_000L), videoStream, audioUrl);
        } catch (Exception error) {
            throw new CompletionException(error);
        }
    }

    private void captureBaseline() {
        memoryBaseline = RealMediaLifecycleScenario.currentOwnedBytesByCategory();
        uploadBaseline = VideoBillboardPreview.benchUploadResources();
        stereoBaseline = StereoOpenALHandler.lifecycleSnapshot();
        tapBaseline = OpenALTappedAudioInputStream.lifecycleSnapshot();
        videoCloseBaseline = VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
        audioCloseBaseline = AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
        pendingNativeDeleteBaseline = OpenALSpatialAudio.pendingNativeDeleteBatches();
        if (uploadBaseline.rgbaTexture() || uploadBaseline.yuvTextures()
                || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != 0
                || videoCloseBaseline != 0 || audioCloseBaseline != 0 || pendingNativeDeleteBaseline != 0) {
            throw new AssertionError("Paired real-media baseline is not idle: " + describeResources(null));
        }
        baselineCaptured = true;
    }

    private void startMedia() {
        video = RealMediaLifecycleScenario.RealVideoStage.start(
                media.videoStream(), memoryBaseline, uploadBaseline.gpuPboBytes());
        audio = RealMediaLifecycleScenario.RealAudioStage.start(
                media.audioUrl(), audioOwner, mediaSession, media.durationMillis());
        mediaStarted = true;
    }

    private void pollLoadedMedia(BenchClientContext context) {
        throwStageFailures();
        StereoOpenALHandler.DiagnosticSnapshot output =
                ClientAudioOutputRegistry.getSessionStereoSnapshot(mediaSession).orElse(null);
        boolean audioReady = output != null && output.started()
                && output.firstAudiblePcm().samples() >= 1_024L && output.inputSamples() > 0L;
        if (irisExpected && IrisShaderpackCompat.isShaderPackInUse()) {
            irisObserved = true;
        }
        if (!video.loaded() || !audioReady || irisExpected && !irisObserved) {
            return;
        }
        requirePcmQuality("paired real Bilibili client " + clientIndex, output.firstAudiblePcm());
        if (!video.directFrame() || video.frameBytes() <= 0L || !video.yuvTextureObserved()
                || !video.decoderNv12Observed() || !video.pboObserved()) {
            throw new AssertionError("Paired real video did not exercise direct NV12 texture/PBO: " + video);
        }
        var lease = ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS);
        if (!lease.active() || !lease.leasePresent() || onlinePlayers(context) != 2) {
            return;
        }
        mediaLoaded = true;
    }

    private boolean mediaStillLoaded() {
        StereoOpenALHandler.DiagnosticSnapshot output =
                ClientAudioOutputRegistry.getSessionStereoSnapshot(mediaSession).orElse(null);
        return video != null && video.loaded() && !video.finished()
                && output != null && output.started() && output.inputSamples() > 0L;
    }

    private void stopMedia() {
        if (!stopIssued.compareAndSet(false, true)) {
            return;
        }
        if (video != null) {
            video.stop();
        }
        if (audio != null) {
            audio.stop();
        }
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
        VideoBillboardPreview.releaseBenchUploadResources();
    }

    private void tickResourceClosures(BenchClientContext context) {
        ClientAudioOutputRegistry.updatePositions(new float[] {
                (float) context.player().getX(), (float) context.player().getEyeY(),
                (float) context.player().getZ()
        });
        OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
        VideoCloseDiagnostics.tickGlobal();
    }

    private void recordMetrics(BenchClientContext context) {
        context.metrics().record(MULTI_CLIENT_REAL_MEDIA_LOADED, mediaLoaded ? 1L : 0L);
        context.metrics().record(MULTI_CLIENT_REAL_MEDIA_CONVERGED, converged ? 1L : 0L);
        context.metrics().record(MULTI_CLIENT_REAL_MEDIA_OWNED_BYTES,
                RealMediaLifecycleScenario.currentOwnedBytes());
        context.metrics().record(MULTI_CLIENT_REAL_MEDIA_IRIS, irisObserved ? 1L : 0L);
    }

    private boolean idleForBaseline() {
        return !ClientAudioOutputRegistry.isActive()
                && HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() == 0
                && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                && !VideoBillboardPreview.benchUploadResources().rgbaTexture()
                && !VideoBillboardPreview.benchUploadResources().yuvTextures();
    }

    private boolean resourcesAtBaseline() {
        if (video == null || audio == null || !video.finished() || !audio.finished()) {
            return false;
        }
        VideoBillboardPreview.BenchUploadResources upload = VideoBillboardPreview.benchUploadResources();
        return !ClientAudioOutputRegistry.isActive()
                && StereoOpenALHandler.lifecycleSnapshot().activeInstances() == stereoBaseline.activeInstances()
                && OpenALTappedAudioInputStream.lifecycleSnapshot().activeInstances()
                        == tapBaseline.activeInstances()
                && HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() == 0
                && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations()
                        == videoCloseBaseline
                && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations()
                        == audioCloseBaseline
                && OpenALSpatialAudio.pendingNativeDeleteBatches() == pendingNativeDeleteBaseline
                && upload.rgbaTexture() == uploadBaseline.rgbaTexture()
                && upload.yuvTextures() == uploadBaseline.yuvTextures()
                && upload.textureStagingBytes() == uploadBaseline.textureStagingBytes()
                && upload.gpuPboBytes() == uploadBaseline.gpuPboBytes()
                && Arrays.equals(memoryBaseline,
                        RealMediaLifecycleScenario.currentOwnedBytesByCategory());
    }

    private void cleanupGlobalResources() {
        ModernTurntableVideoClient.clear();
        VideoBillboardPreview.stop();
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
        VideoBillboardPreview.releaseBenchUploadResources();
        OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
    }

    private void throwResolutionFailure() {
        Throwable error = resolutionFailure.get();
        if (error != null) {
            throw new AssertionError("Failed to resolve paired real Bilibili media", error);
        }
    }

    private void throwStageFailures() {
        if (video != null) {
            video.throwIfFailed();
        }
        if (audio != null) {
            audio.throwIfFailed();
        }
    }

    private int onlinePlayers(BenchClientContext context) {
        return context.minecraft().getConnection() == null ? 0
                : context.minecraft().getConnection().getOnlinePlayers().size();
    }

    private String describeResources(BenchClientContext context) {
        return "index=" + clientIndex + " online=" + (context != null ? onlinePlayers(context) : -1)
                + " loaded=" + mediaLoaded + " peerExit=" + observedPeerExit
                + " survivorLoaded=" + survivorStayedLoaded + " stop=" + stopIssued
                + " converged=" + converged + " iris=" + irisObserved + '/' + irisExpected
                + " video=" + video + " audio=" + audio
                + " upload=" + VideoBillboardPreview.benchUploadResources()
                + " http=" + HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime())
                + " videoClose=" + VideoCloseDiagnostics.global().snapshot(System.nanoTime())
                + " audioClose=" + AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime())
                + " stereo=" + StereoOpenALHandler.lifecycleSnapshot()
                + " tap=" + OpenALTappedAudioInputStream.lifecycleSnapshot()
                + " pendingNative=" + OpenALSpatialAudio.pendingNativeDeleteBatches()
                + " memory=" + Arrays.toString(RealMediaLifecycleScenario.currentOwnedBytesByCategory());
    }

    private record PairedResolvedMedia(long durationMillis,
            BiliApiClient.VideoStream videoStream, String audioUrl) {
    }
}
