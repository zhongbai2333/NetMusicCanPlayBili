package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requirePcmQuality;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureFlags;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureProperties;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;

import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class RealMediaLifecycleScenario implements BenchClientScenario {
    private static final BenchMetricDescriptor COMPLETED_ROUNDS = new BenchMetricDescriptor(
            "ncpb.real_media_lifecycle.completed_rounds", "count", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor HTTP_ACTIVE = new BenchMetricDescriptor(
            "ncpb.real_media_lifecycle.http_active", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor VIDEO_FRAME_BYTES = new BenchMetricDescriptor(
            "ncpb.real_media_lifecycle.video_frame_bytes", "bytes", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor GPU_PBO_BYTES = new BenchMetricDescriptor(
            "ncpb.real_media_lifecycle.gpu_pbo_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor AUDIO_SAMPLES = new BenchMetricDescriptor(
            "ncpb.real_media_lifecycle.audio_samples", "count", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor OWNED_BYTES = new BenchMetricDescriptor(
            "ncpb.real_media_lifecycle.owned_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor CONVERGENCE_MILLIS = new BenchMetricDescriptor(
            "ncpb.real_media_lifecycle.convergence_millis", "milliseconds", MetricDirection.LOWER_IS_BETTER);

    private final VideoFeatureProperties.RealMediaLifecycle properties =
            VideoFeatureProperties.realMediaLifecycle();
    private final AtomicReference<Throwable> resolutionFailure = new AtomicReference<>();
    private CompletableFuture<ResolvedMedia> resolution;
    private ResolvedMedia media;
    private UUID audioOwner;
    private long[] memoryBaseline;
    private VideoBillboardPreview.BenchUploadResources uploadBaseline;
    private StereoOpenALHandler.LifecycleSnapshot stereoBaseline;
    private OpenALTappedAudioInputStream.LifecycleSnapshot tapBaseline;
    private int videoCloseBaseline;
    private int audioCloseBaseline;
    private int pendingNativeDeleteBaseline;
    private int completedRounds;
    private int cycleTicks;
    private long cycleStartedNanos;
    private long convergenceStartedNanos;
    private long maxConvergenceMillis;
    private long cycleHttpStarted;
    private long cycleStereoCreated;
    private long cycleStereoCleaned;
    private long cycleTapCreated;
    private long cycleTapClosed;
    private RealVideoStage video;
    private RealAudioStage audio;
    private PlaybackSessionId cycleSession;
    private CycleState cycleState = CycleState.READY;
    private boolean baselineCaptured;

    @Override
    public void setup(BenchClientContext context) {
        cleanupGlobalResources();
        audioOwner = context.player().getUUID();
        ClientAudioOutputRegistry.setOwnerVolume(audioOwner, 1.0F);
        resolution = CompletableFuture.supplyAsync(this::resolveMedia)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        resolutionFailure.compareAndSet(null, unwrapCompletion(error));
                    }
                });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        tickResourceClosures(context);
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
        captureBaseline();
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        tickResourceClosures(context);
        recordMetrics(context);
        if (!baselineCaptured || media == null) {
            throw new AssertionError("real media lifecycle baseline was not captured");
        }
        cycleTicks++;
        if (cycleTicks > properties.cycleTimeoutTicks()) {
            throw new AssertionError("Real media lifecycle cycle timed out: round=" + completedRounds
                    + " state=" + cycleState + " video=" + video + " audio=" + audio
                    + " diagnostics=" + describeResources());
        }

        switch (cycleState) {
            case READY -> startCycle();
            case LOADING -> pollLoadedCycle();
            case CLOSING -> pollCycleConvergence();
        }
        return completedRounds >= properties.rounds()
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (completedRounds != properties.rounds() || cycleState != CycleState.READY
                || !resourcesAtBaseline()) {
            throw new AssertionError("Expected " + properties.rounds()
                    + " real loaded lifecycle rounds, got " + completedRounds + ": " + describeResources());
        }
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        if (stereo.instancesCreated() != stereoBaseline.instancesCreated() + properties.rounds()
                || stereo.cleanupsStarted() != stereoBaseline.cleanupsStarted() + properties.rounds()
                || stereo.cleanupsCompleted() != stereoBaseline.cleanupsCompleted() + properties.rounds()
                || tap.instancesCreated() != tapBaseline.instancesCreated()
                || tap.closesCompleted() != tapBaseline.closesCompleted()) {
            throw new AssertionError("Each real Bilibili audio/OpenAL cycle must close exactly once: stereo="
                    + stereoBaseline + " -> " + stereo + ", tap=" + tapBaseline + " -> " + tap);
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        stopCycleResources();
        cleanupGlobalResources();
    }

    private ResolvedMedia resolveMedia() {
        try {
            BiliApiClient.VideoId videoId = BiliApiClient.extractVideoId(properties.videoId());
            if (videoId == null) {
                throw new IOException("invalid Bilibili video id: " + properties.videoId());
            }
            BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(videoId);
            BiliApiClient.VideoStreamPlan plan = BiliApiClient.getVideoStreamPlan(
                    videoId, info.cid(), properties.quality());
            // Lifecycle convergence is codec-independent. Prefer H.264 here so unsupported AV1 hardware on a
            // matrix host cannot turn this resource test into an unrelated codec-capability failure.
            BiliApiClient.VideoStream videoStream = !plan.h264Candidates().isEmpty()
                    ? plan.h264Candidates().getFirst() : plan.preferred();
            String audioUrl = BiliApiClient.getBestAudioUrl(videoId, info.cid(), false);
            if (videoStream.baseUrl().isBlank() || audioUrl == null || audioUrl.isBlank()) {
                throw new IOException("Bilibili playurl returned an empty media URL");
            }
            return new ResolvedMedia(info.displayTitle(), Math.max(1L, info.duration() * 1_000L),
                    videoStream, audioUrl);
        } catch (Exception error) {
            throw new CompletionException(error);
        }
    }

    private void captureBaseline() {
        memoryBaseline = currentOwnedBytesByCategory();
        uploadBaseline = VideoBillboardPreview.benchUploadResources();
        stereoBaseline = StereoOpenALHandler.lifecycleSnapshot();
        tapBaseline = OpenALTappedAudioInputStream.lifecycleSnapshot();
        videoCloseBaseline = VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
        audioCloseBaseline = AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
        pendingNativeDeleteBaseline = OpenALSpatialAudio.pendingNativeDeleteBatches();
        if (uploadBaseline.rgbaTexture() || uploadBaseline.yuvTextures()
                || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != 0
                || videoCloseBaseline != 0 || audioCloseBaseline != 0 || pendingNativeDeleteBaseline != 0) {
            throw new AssertionError("Real media lifecycle baseline is not idle: " + describeResources());
        }
        baselineCaptured = true;
    }

    private void startCycle() {
        cycleSession = PlaybackSessionId.of("bench-real-media-lifecycle-" + completedRounds);
        HttpRequestCloseDiagnostics.Snapshot http =
                HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        cycleHttpStarted = http.startedRequests();
        cycleStereoCreated = stereo.instancesCreated();
        cycleStereoCleaned = stereo.cleanupsCompleted();
        cycleTapCreated = tap.instancesCreated();
        cycleTapClosed = tap.closesCompleted();
        cycleStartedNanos = System.nanoTime();
        convergenceStartedNanos = 0L;
        video = RealVideoStage.start(media.videoStream(), memoryBaseline,
                uploadBaseline.gpuPboBytes());
        audio = RealAudioStage.start(media.audioUrl(), audioOwner, cycleSession, media.durationMillis());
        cycleState = CycleState.LOADING;
        cycleTicks = 0;
    }

    private void pollLoadedCycle() {
        video.throwIfFailed();
        audio.throwIfFailed();
        StereoOpenALHandler.DiagnosticSnapshot output =
                ClientAudioOutputRegistry.getSessionStereoSnapshot(cycleSession).orElse(null);
        boolean audioReady = output != null && output.started()
                && output.firstAudiblePcm().samples() >= 1_024L
                && output.inputSamples() > 0L;
        if (!video.loaded() || !audioReady) {
            return;
        }
        requirePcmQuality("real Bilibili lifecycle round " + completedRounds, output.firstAudiblePcm());
        if (!video.directFrame() || video.frameBytes() <= 0L || !video.yuvTextureObserved()
                || !video.decoderNv12Observed() || !video.pboObserved()) {
            throw new AssertionError("Real Bilibili video cycle did not exercise direct NV12 texture/PBO: "
                    + video);
        }
        HttpRequestCloseDiagnostics.Snapshot http =
                HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
        if (http.startedRequests() <= cycleHttpStarted) {
            throw new AssertionError("Real media cycle did not start an instrumented HTTP request");
        }
        convergenceStartedNanos = System.nanoTime();
        stopCycleResources();
        cycleState = CycleState.CLOSING;
        cycleTicks = 0;
    }

    private void pollCycleConvergence() {
        video.throwIfFailed();
        audio.throwIfFailed();
        if (!resourcesAtBaseline()) {
            return;
        }
        HttpRequestCloseDiagnostics.Snapshot http =
                HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        if (http.startedRequests() <= cycleHttpStarted
                || stereo.instancesCreated() != cycleStereoCreated + 1L
                || stereo.cleanupsCompleted() != cycleStereoCleaned + 1L
                || tap.instancesCreated() != cycleTapCreated
                || tap.closesCompleted() != cycleTapClosed
                || !video.terminatedNormally() || !audio.streamClosed()) {
            throw new AssertionError("Real loaded cycle did not converge exactly once: round="
                    + completedRounds + " video=" + video + " audio=" + audio + " stereo=" + stereo
                    + " tap=" + tap + " http=" + http);
        }
        long convergenceMillis = TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, System.nanoTime() - convergenceStartedNanos));
        maxConvergenceMillis = Math.max(maxConvergenceMillis, convergenceMillis);
        completedRounds++;
        video = null;
        audio = null;
        cycleSession = null;
        cycleState = CycleState.READY;
        cycleTicks = 0;
    }

    private void stopCycleResources() {
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
        HttpRequestCloseDiagnostics.Snapshot http =
                HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
        StereoOpenALHandler.DiagnosticSnapshot output = cycleSession != null
                ? ClientAudioOutputRegistry.getSessionStereoSnapshot(cycleSession).orElse(null) : null;
        context.metrics().record(COMPLETED_ROUNDS, completedRounds);
        context.metrics().record(HTTP_ACTIVE, http.activeRequests());
        context.metrics().record(VIDEO_FRAME_BYTES, video != null ? video.frameBytes() : 0L);
        context.metrics().record(GPU_PBO_BYTES,
                MemoryResourceTracker.usage(MemoryResourceTracker.Category.GPU_PBO).currentBytes());
        context.metrics().record(AUDIO_SAMPLES, output != null ? output.firstAudiblePcm().samples() : 0L);
        context.metrics().record(OWNED_BYTES, currentOwnedBytes());
        context.metrics().record(CONVERGENCE_MILLIS, maxConvergenceMillis);
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
        if ((video != null && !video.finished()) || (audio != null && !audio.finished())) {
            return false;
        }
        VideoBillboardPreview.BenchUploadResources upload = VideoBillboardPreview.benchUploadResources();
        if (ClientAudioOutputRegistry.isActive()
                || StereoOpenALHandler.lifecycleSnapshot().activeInstances() != stereoBaseline.activeInstances()
                || OpenALTappedAudioInputStream.lifecycleSnapshot().activeInstances() != tapBaseline.activeInstances()
                || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != 0
                || VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations()
                        != videoCloseBaseline
                || AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations()
                        != audioCloseBaseline
                || OpenALSpatialAudio.pendingNativeDeleteBatches() != pendingNativeDeleteBaseline
                || upload.rgbaTexture() != uploadBaseline.rgbaTexture()
                || upload.yuvTextures() != uploadBaseline.yuvTextures()
                || upload.textureStagingBytes() != uploadBaseline.textureStagingBytes()
                || upload.gpuPboBytes() != uploadBaseline.gpuPboBytes()) {
            return false;
        }
        long[] current = currentOwnedBytesByCategory();
        return Arrays.equals(memoryBaseline, current);
    }

    private String describeResources() {
        return "state=" + cycleState + " round=" + completedRounds + '/' + properties.rounds()
                + " upload=" + VideoBillboardPreview.benchUploadResources()
                + " http=" + HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime())
                + " videoClose=" + VideoCloseDiagnostics.global().snapshot(System.nanoTime())
                + " audioClose=" + AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime())
                + " stereo=" + StereoOpenALHandler.lifecycleSnapshot()
                + " tap=" + OpenALTappedAudioInputStream.lifecycleSnapshot()
                + " pendingNative=" + OpenALSpatialAudio.pendingNativeDeleteBatches()
                + " memory=" + Arrays.toString(currentOwnedBytesByCategory());
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
            throw new AssertionError("Failed to resolve real Bilibili lifecycle media", error);
        }
    }

    static Throwable unwrapCompletion(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static long[] currentOwnedBytesByCategory() {
        MemoryResourceTracker.Category[] categories = MemoryResourceTracker.Category.values();
        long[] current = new long[categories.length];
        for (int i = 0; i < categories.length; i++) {
            current[i] = MemoryResourceTracker.usage(categories[i]).currentBytes();
        }
        return current;
    }

    static long currentOwnedBytes() {
        return Arrays.stream(currentOwnedBytesByCategory()).sum();
    }

    private enum CycleState {
        READY,
        LOADING,
        CLOSING
    }

    private record ResolvedMedia(String title, long durationMillis,
            BiliApiClient.VideoStream videoStream, String audioUrl) {
    }

    static final class RealVideoStage {
        private final BiliApiClient.VideoStream stream;
        private final long decoderNv12Baseline;
        private final long pboBaseline;
        private final AtomicReference<Fmp4NativeVideoDecoder> decoder = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final AtomicBoolean loaded = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean terminatedNormally = new AtomicBoolean();
        private final Thread worker;
        private volatile long frameBytes;
        private volatile long uploadNanos;
        private volatile boolean directFrame;
        private volatile boolean yuvTextureObserved;
        private volatile boolean decoderNv12Observed;
        private volatile boolean pboObserved;

        private RealVideoStage(BiliApiClient.VideoStream stream, long[] memoryBaseline, long pboBaseline) {
            this.stream = stream;
            this.decoderNv12Baseline = memoryBaseline[MemoryResourceTracker.Category.DECODER_NV12.ordinal()];
            this.pboBaseline = pboBaseline;
            worker = NetMusicThreadFactory.daemonThread("RealMediaLifecycle-video", this::run);
            worker.start();
        }

        static RealVideoStage start(BiliApiClient.VideoStream stream, long[] memoryBaseline, long pboBaseline) {
            return new RealVideoStage(stream, memoryBaseline, pboBaseline);
        }

        private void run() {
            Fmp4NativeVideoDecoder opened = null;
            try {
                opened = openDecoder();
                decoder.set(opened);
                try (Fmp4NativeVideoDecoder.DecodedFrame frame = opened.getNextDecodedFrame()) {
                    if (frame == null) {
                        throw new IOException("real Bilibili video decoder reached EOF before its first frame");
                    }
                    frameBytes = frame.byteLength();
                    directFrame = frame.buffer() != null;
                    decoderNv12Observed = MemoryResourceTracker
                            .usage(MemoryResourceTracker.Category.DECODER_NV12).currentBytes()
                            > decoderNv12Baseline;
                    uploadNanos = VideoBillboardPreview.uploadDecodedFrameSyncForBench(
                            frame, Math.max(1, stream.width()), Math.max(1, stream.height()));
                    if (uploadNanos < 0L) {
                        throw new IOException("real Bilibili decoded frame GPU upload failed");
                    }
                    VideoBillboardPreview.BenchUploadResources resources =
                            VideoBillboardPreview.benchUploadResources();
                    yuvTextureObserved = resources.yuvTextures();
                    pboObserved = resources.gpuPboBytes() > pboBaseline;
                    loaded.set(true);
                }
                while (!stopRequested.get()) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(10L);
                    } catch (InterruptedException interrupted) {
                        if (!stopRequested.get()) {
                            throw interrupted;
                        }
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Throwable error) {
                if (!stopRequested.get()) {
                    failure.compareAndSet(null, error);
                }
            } finally {
                // stop() interrupts the stage worker only to leave its hold loop. Do not let that expected
                // cancellation interrupt short-circuit decoder.close()/terminationFuture(), otherwise the
                // benchmark would report a lifecycle failure before the physical native barrier is observed.
                if (stopRequested.get()) {
                    Thread.interrupted();
                }
                if (opened != null) {
                    try {
                        opened.requestClose();
                        opened.close();
                        awaitExpectedTermination(opened);
                        terminatedNormally.set(true);
                    } catch (Throwable error) {
                        failure.compareAndSet(null, unwrapCompletion(error));
                    }
                }
                decoder.set(null);
                finished.set(true);
            }
        }

        private void awaitExpectedTermination(Fmp4NativeVideoDecoder opened) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20L);
            while (true) {
                if (stopRequested.get()) {
                    Thread.interrupted();
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    throw new java.util.concurrent.TimeoutException(
                            "real Bilibili video decoder termination timed out");
                }
                try {
                    opened.terminationFuture().get(remaining, TimeUnit.NANOSECONDS);
                    return;
                } catch (InterruptedException interrupted) {
                    if (!stopRequested.get()) {
                        throw interrupted;
                    }
                    // stop() may race with the transition from the hold loop into this barrier.
                    // Expected cancellation must not turn physical termination into a false failure.
                }
            }
        }

        private Fmp4NativeVideoDecoder openDecoder() throws IOException {
            IOException last = null;
            for (String hwaccel : VideoFeatureFlags.requestedHwaccelCandidates()) {
                try {
                    return new Fmp4NativeVideoDecoder(stream.baseUrl(), stream.codecId(),
                            Math.max(1, stream.width()), Math.max(1, stream.height()), 10_000, true,
                            Fmp4NativeVideoDecoder.OutputFormat.NV12, hwaccel, 0L, 0L, 30);
                } catch (IOException error) {
                    last = error;
                }
            }
            throw last != null ? last : new IOException("no native video hwaccel candidate was available");
        }

        void stop() {
            stopRequested.set(true);
            Fmp4NativeVideoDecoder opened = decoder.get();
            if (opened != null) {
                try {
                    opened.requestClose();
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            }
            worker.interrupt();
        }

        void throwIfFailed() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Real Bilibili video lifecycle stage failed: " + error, error);
            }
        }

        boolean loaded() {
            return loaded.get();
        }

        boolean finished() {
            return finished.get();
        }

        boolean terminatedNormally() {
            return terminatedNormally.get();
        }

        boolean directFrame() {
            return directFrame;
        }

        long frameBytes() {
            return frameBytes;
        }

        boolean yuvTextureObserved() {
            return yuvTextureObserved;
        }

        boolean decoderNv12Observed() {
            return decoderNv12Observed;
        }

        boolean pboObserved() {
            return pboObserved;
        }

        @Override
        public String toString() {
            return "RealVideoStage[loaded=" + loaded + ", finished=" + finished + ", terminated="
                    + terminatedNormally + ", frameBytes=" + frameBytes + ", uploadNanos=" + uploadNanos
                    + ", direct=" + directFrame + ", yuv=" + yuvTextureObserved + ", pbo=" + pboObserved
                    + ", decoderNv12=" + decoderNv12Observed + ", failure=" + failure + ']';
        }
    }

    static final class RealAudioStage {
        private static final int READ_BUFFER_BYTES = 32 * 1024;
        private final AtomicReference<AudioInputStream> stream = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<HttpAudioStreamHandler.RegisteredRequest> registered =
                new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final AtomicBoolean streamCloseCompleted = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final Thread reader;

        private RealAudioStage(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId,
                long totalMillis) {
            reader = NetMusicThreadFactory.daemonThread("RealMediaLifecycle-audio-" + sessionId.value(),
                    () -> run(mediaUrl, ownerId, sessionId, totalMillis));
            reader.start();
        }

        static RealAudioStage start(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId,
                long totalMillis) {
            return new RealAudioStage(mediaUrl, ownerId, sessionId, totalMillis);
        }

        private void run(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId, long totalMillis) {
            try {
                PlaybackRequest request = PlaybackRequest.now(mediaUrl, null, sessionId.value(), 0L,
                        Math.max(1L, totalMillis), ownerId, null);
                HttpAudioStreamHandler.RegisteredRequest requestUrl =
                        HttpAudioStreamHandler.registerRequest(request);
                registered.set(requestUrl);
                if (cancelled.get()) {
                    requestUrl.requestToken().ifPresent(HttpAudioStreamHandler::cancelRequest);
                    return;
                }
                AudioInputStream opened = new HttpAudioStreamHandler().handle(
                        URI.create(requestUrl.url()).toURL());
                stream.set(opened);
                byte[] buffer = new byte[READ_BUFFER_BYTES];
                while (!cancelled.get() && opened.read(buffer, 0, buffer.length) >= 0) {
                    // Decoder/OpenAL backpressure intentionally remains on this daemon worker.
                }
            } catch (Throwable error) {
                if (!cancelled.get()) {
                    failure.compareAndSet(null, error);
                }
            } finally {
                closeStream();
                finished.set(true);
            }
        }

        void stop() {
            cancelled.set(true);
            if (!stopRequested.compareAndSet(false, true)) {
                return;
            }
            reader.interrupt();
            NetMusicThreadFactory.daemonThread("RealMediaLifecycle-audio-close", () -> {
                HttpAudioStreamHandler.RegisteredRequest request = registered.get();
                if (request != null) {
                    request.requestToken().ifPresent(HttpAudioStreamHandler::cancelRequest);
                }
                closeStream();
            }).start();
        }

        void throwIfFailed() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Real Bilibili audio lifecycle stage failed: " + error, error);
            }
        }

        boolean finished() {
            return finished.get();
        }

        boolean streamClosed() {
            return streamCloseCompleted.get();
        }

        private void closeStream() {
            AudioInputStream value = stream.get();
            if (value == null) {
                return;
            }
            try {
                value.close();
                streamCloseCompleted.set(true);
            } catch (IOException error) {
                if (!cancelled.get()) {
                    failure.compareAndSet(null, error);
                }
            }
        }

        @Override
        public String toString() {
            return "RealAudioStage[finished=" + finished + ", streamClosed=" + streamClosed()
                    + ", failure=" + failure + ']';
        }
    }
}
