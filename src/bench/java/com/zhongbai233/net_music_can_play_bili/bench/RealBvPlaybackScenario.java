package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requirePcmQuality;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.BiliRealVideoPlaybackBench;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureProperties;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

final class RealBvPlaybackScenario implements BenchClientScenario {
    private static final BenchMetricDescriptor DECODED_STAGES = new BenchMetricDescriptor(
            "ncpb.real_bv.decoded_stages", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor DECODED_FRAMES = new BenchMetricDescriptor(
            "ncpb.real_bv.decoded_frames", "count", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor AUDIO_SAMPLES = new BenchMetricDescriptor(
            "ncpb.real_bv.audio_samples", "count", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor AUDIO_INPUT_SAMPLES = new BenchMetricDescriptor(
            "ncpb.real_bv.audio_input_samples", "count", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor AUDIO_PCM_RMS = new BenchMetricDescriptor(
            "ncpb.real_bv.audio_pcm_rms", "ratio", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor ACTIVE_CLOSES = new BenchMetricDescriptor(
            "ncpb.real_bv.active_closes", "count", MetricDirection.LOWER_IS_BETTER);
    private static final String AUDIO_SESSION_ID = "bench-real-bv-audio";

    private final AtomicReference<Throwable> audioResolutionFailure = new AtomicReference<>();
    private CompletableFuture<ResolvedAudio> audioResolution;
    private ResolvedAudio resolvedAudio;
    private RealMediaLifecycleScenario.RealAudioStage audio;
    private PlaybackSessionId audioSession;
    private UUID audioOwner;
    private BiliRealVideoPlaybackBench.RunSnapshot finalSnapshot;
    private StereoOpenALHandler.PcmQuality decodedAudioPcm =
            new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D);
    private long decodedAudioInputSamples;
    private boolean audioDecoded;
    private boolean cleanupRequested;

    @Override
    public void setup(BenchClientContext context) {
        cleanupMedia();
        audioOwner = context.player().getUUID();
        audioSession = PlaybackSessionId.of(AUDIO_SESSION_ID);
        ClientAudioOutputRegistry.setOwnerVolume(audioOwner, 1.0F);
        audioResolution = CompletableFuture.supplyAsync(this::resolveAudio)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        audioResolutionFailure.compareAndSet(null,
                                RealMediaLifecycleScenario.unwrapCompletion(error));
                    }
                });
        if (!BiliRealVideoPlaybackBench.tryStart()) {
            throw new AssertionError("Real BV bench flags are not enabled");
        }
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        tickResourceClosures(context);
        throwAudioResolutionFailure();
        if (!context.environment().readiness().ready() || context.frames().sampleCount() < 2
                || audioResolution == null || !audioResolution.isDone()) {
            return BenchClientStepResult.CONTINUE;
        }
        if (resolvedAudio == null) {
            resolvedAudio = audioResolution.join();
        }
        if (audio == null) {
            audio = RealMediaLifecycleScenario.RealAudioStage.start(
                    resolvedAudio.audioUrl(), audioOwner, audioSession, resolvedAudio.durationMillis());
        }
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        tickResourceClosures(context);
        throwAudioResolutionFailure();
        if (audio == null) {
            throw new AssertionError("Real BV audio stage was not started");
        }
        audio.throwIfFailed();
        StereoOpenALHandler.DiagnosticSnapshot audioOutput =
                ClientAudioOutputRegistry.getSessionStereoSnapshot(audioSession).orElse(null);
        if (!audioDecoded && audioOutput != null && audioOutput.started()
                && audioOutput.firstAudiblePcm().samples() >= 1_024L
                && audioOutput.inputSamples() > 0L) {
            requirePcmQuality("real Bilibili DASH audio", audioOutput.firstAudiblePcm());
            decodedAudioPcm = audioOutput.firstAudiblePcm();
            decodedAudioInputSamples = audioOutput.inputSamples();
            audioDecoded = true;
        }

        BiliRealVideoPlaybackBench.RunSnapshot snapshot = BiliRealVideoPlaybackBench.snapshot();
        context.metrics().record(DECODED_STAGES, snapshot.decodedStages());
        context.metrics().record(DECODED_FRAMES, snapshot.decodedFrames());
        context.metrics().record(AUDIO_SAMPLES,
                audioOutput != null ? audioOutput.firstAudiblePcm().samples() : decodedAudioPcm.samples());
        context.metrics().record(AUDIO_INPUT_SAMPLES,
                audioOutput != null ? audioOutput.inputSamples() : decodedAudioInputSamples);
        context.metrics().record(AUDIO_PCM_RMS,
                audioOutput != null ? audioOutput.firstAudiblePcm().rms() : decodedAudioPcm.rms());
        var videoClose = VideoCloseDiagnostics.global().snapshot(System.nanoTime());
        context.metrics().record(ACTIVE_CLOSES, videoClose.activeOperations());
        if (snapshot.state() == BiliRealVideoPlaybackBench.RunState.FAILED) {
            throw new AssertionError("Real BV video decode failed: " + snapshot);
        }
        if (snapshot.state() != BiliRealVideoPlaybackBench.RunState.SUCCEEDED || !audioDecoded) {
            return BenchClientStepResult.CONTINUE;
        }
        finalSnapshot = snapshot;
        if (!cleanupRequested) {
            cleanupRequested = true;
            cleanupMedia();
            return BenchClientStepResult.CONTINUE;
        }
        return resourcesConverged(audio) ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (finalSnapshot == null || finalSnapshot.state() != BiliRealVideoPlaybackBench.RunState.SUCCEEDED
                || finalSnapshot.decodedStages() <= 0 || finalSnapshot.decodedFrames() <= 0) {
            throw new AssertionError("Real BV bench did not decode video: " + finalSnapshot);
        }
        if (!audioDecoded || decodedAudioPcm.samples() < 1_024L || decodedAudioInputSamples <= 0L) {
            throw new AssertionError("Real BV bench did not decode audible audio: pcm=" + decodedAudioPcm
                    + " inputSamples=" + decodedAudioInputSamples);
        }
        if (!finalSnapshot.videoId().equals(VideoFeatureProperties.realMediaLifecycle().videoId())) {
            throw new AssertionError("Real BV bench decoded an unexpected video: " + finalSnapshot);
        }
        if (!resourcesConverged(audio)) {
            throw new AssertionError("Real BV audio/video resources did not converge: video="
                    + ModernTurntableVideoClient.videoLifecycleDiagnostics() + " audio=" + audio
                    + " audioClose=" + AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime())
                    + " openalPending=" + OpenALSpatialAudio.pendingNativeDeleteBatches());
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        cleanupMedia();
    }

    private ResolvedAudio resolveAudio() {
        VideoFeatureProperties.RealMediaLifecycle properties = VideoFeatureProperties.realMediaLifecycle();
        try {
            BiliApiClient.VideoId videoId = BiliApiClient.extractVideoId(properties.videoId());
            if (videoId == null) {
                throw new IOException("invalid Bilibili video id: " + properties.videoId());
            }
            BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(videoId);
            String audioUrl = BiliApiClient.getBestAudioUrl(videoId, info.cid(), false);
            if (audioUrl == null || audioUrl.isBlank()) {
                throw new IOException("Bilibili playurl returned an empty DASH audio URL");
            }
            return new ResolvedAudio(Math.max(1L, info.duration() * 1_000L), audioUrl);
        } catch (Exception error) {
            throw new CompletionException(error);
        }
    }

    private void tickResourceClosures(BenchClientContext context) {
        ClientAudioOutputRegistry.updatePositions(new float[] {
                (float) context.player().getX(), (float) context.player().getEyeY(),
                (float) context.player().getZ()
        });
        OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
        VideoCloseDiagnostics.tickGlobal();
    }

    private void throwAudioResolutionFailure() {
        Throwable error = audioResolutionFailure.get();
        if (error != null) {
            throw new AssertionError("Real BV audio resolve failed before decode", error);
        }
    }

    private void cleanupMedia() {
        if (audio != null) {
            audio.stop();
        }
        ModernTurntableVideoClient.clear();
        VideoBillboardPreview.stop();
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
        VideoBillboardPreview.releaseBenchUploadResources();
        OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
    }

    private static boolean resourcesConverged(RealMediaLifecycleScenario.RealAudioStage audio) {
        var lifecycle = ModernTurntableVideoClient.videoLifecycleDiagnostics();
        if (audio == null || !audio.finished() || !audio.streamClosed()
                || ClientAudioOutputRegistry.isActive()
                || lifecycle.activeRequests() != 0 || lifecycle.pendingRequests() != 0
                || lifecycle.resources().instances() != 0
                || lifecycle.resources().activeCloseZombies() != 0
                || VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != 0
                || AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != 0
                || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != 0) {
            return false;
        }
        if (OpenALSpatialAudio.pendingNativeDeleteBatches() != 0) {
            return false;
        }
        for (MemoryResourceTracker.Category category : MemoryResourceTracker.Category.values()) {
            if (MemoryResourceTracker.usage(category).currentBytes() != 0L) {
                return false;
            }
        }
        return true;
    }

    private record ResolvedAudio(long durationMillis, String audioUrl) {
    }
}
