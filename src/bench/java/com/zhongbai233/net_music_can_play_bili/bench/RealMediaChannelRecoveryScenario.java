package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requirePcmQuality;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureProperties;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedMediaSound;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Reproduces the streaming-channel exhaustion window seen when preview media is
 * replaced before its deferred decoder has opened a stream.
 *
 * <p>The churn sounds deliberately reserve a Minecraft streaming channel while
 * keeping decode admission closed. Each sound is then stopped before stream
 * creation. Twelve rounds exceed Minecraft's eight-channel streaming pool. A
 * thirteenth real MP3 plus a native H.264 video must still start, and both
 * physical pipelines must return to their captured idle baseline.</p>
 */
final class RealMediaChannelRecoveryScenario implements BenchClientScenario {
    private static final int CHURN_ROUNDS = 12;
    private static final int RESERVATION_HOLD_TICKS = 4;
    private static final int RESERVATION_TIMEOUT_TICKS = 40;
    private static final int RELEASE_SETTLE_TICKS = 2;
    private static final long VIDEO_ADVANCE_MILLIS = 250L;
    private static final long TOTAL_MILLIS = 360_000L;

    private static final BenchMetricDescriptor CHURN_COMPLETED = new BenchMetricDescriptor(
            "ncpb.media_channel_recovery.churn_completed", "count", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor PLAY_REJECTIONS = new BenchMetricDescriptor(
            "ncpb.media_channel_recovery.play_rejections", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor STREAMING_STARTS = new BenchMetricDescriptor(
            "ncpb.media_channel_recovery.streaming_starts", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor FINAL_STREAMING_STARTS = new BenchMetricDescriptor(
            "ncpb.media_channel_recovery.final_streaming_starts", "count", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor VIDEO_HAS_FRAME = new BenchMetricDescriptor(
            "ncpb.media_channel_recovery.video_has_frame", "state", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor VIDEO_CLOSE_ACTIVE = new BenchMetricDescriptor(
            "ncpb.media_channel_recovery.video_close_active", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor VIDEO_CLOSE_ZOMBIES = new BenchMetricDescriptor(
            "ncpb.media_channel_recovery.video_close_zombies", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor OWNED_BYTES = new BenchMetricDescriptor(
            "ncpb.media_channel_recovery.owned_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);

    private final AudioStreamProperties.RealMp3Bench audioProperties = AudioStreamProperties.realMp3Bench();
    private final VideoFeatureProperties.RealMediaLifecycle videoProperties =
            VideoFeatureProperties.realMediaLifecycle();
    private final PlaybackSessionId finalSession = PlaybackSessionId.of("bench-media-channel-recovery-final");
    private final Set<BenchSound> trackedSounds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger streamingStarts = new AtomicInteger();
    private final AtomicInteger finalStreamingStarts = new AtomicInteger();
    private final AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> resolutionFailure = new AtomicReference<>();
    private BenchSound finalSound;
    private final Consumer<PlayStreamingSourceEvent> streamingListener = event -> {
        if (trackedSounds.contains(event.getSound())) {
            streamingStarts.incrementAndGet();
        }
        if (event.getSound() == finalSound) {
            finalStreamingStarts.incrementAndGet();
        }
    };

    private CompletableFuture<ResolvedVideo> videoResolution;
    private ResolvedVideo video;
    private UUID ownerId;
    private BenchSound currentChurnSound;
    private HttpAudioStreamHandler.RegisteredRequest finalRequest;
    private OpenALTappedAudioInputStream.LifecycleSnapshot tapBaseline;
    private StereoOpenALHandler.LifecycleSnapshot stereoBaseline;
    private VideoCloseDiagnostics.Snapshot videoCloseBaseline;
    private int audioCloseBaseline;
    private int httpActiveBaseline;
    private int pendingNativeDeleteBaseline;
    private long[] memoryBaseline;
    private long firstVideoMillis = -1L;
    private long finalVideoMillis = -1L;
    private int playAttempts;
    private int playRejections;
    private int churnCompleted;
    private int phaseTicks;
    private boolean currentObservedActive;
    private boolean baselineCaptured;
    private boolean listenerRegistered;
    private boolean finalAudioStarted;
    private boolean finalVideoStarted;
    private boolean closeStarted;
    private boolean converged;
    private Phase phase = Phase.CHURN_SUBMIT;

    @Override
    public void setup(BenchClientContext context) {
        cleanupGlobalResources();
        ModernTurntablePlaybackTracker.stopAllSounds();
        ownerId = context.player().getUUID();
        ClientAudioOutputRegistry.setOwnerVolume(ownerId, 1.0F);
        NeoForge.EVENT_BUS.addListener(PlayStreamingSourceEvent.class, streamingListener);
        listenerRegistered = true;
        videoResolution = CompletableFuture.supplyAsync(this::resolveVideo)
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
        throwIfFailed();
        if (!context.environment().readiness().ready() || context.frames().sampleCount() < 2
                || videoResolution == null || !videoResolution.isDone() || !idle()) {
            return BenchClientStepResult.CONTINUE;
        }
        if (video == null) {
            video = videoResolution.join();
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
        tickClosures();
        SyncedMediaSound.tickPendingDecodeAdmissions();
        throwIfFailed();
        record(context);
        phaseTicks++;

        switch (phase) {
            case CHURN_SUBMIT -> submitChurnSound(context);
            case CHURN_RESERVED -> stopReservedChurnSound(context);
            case CHURN_RELEASING -> awaitChurnRelease(context);
            case FINAL_SUBMIT -> submitFinalMedia(context);
            case FINAL_LOADING -> awaitFinalMedia(context);
            case FINAL_ADVANCING -> awaitVideoAdvance(context);
            case CLOSING -> {
                if (resourcesConverged(context)) {
                    converged = true;
                    return BenchClientStepResult.COMPLETE;
                }
            }
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        if (!baselineCaptured || churnCompleted != CHURN_ROUNDS || playAttempts != CHURN_ROUNDS + 1
                || playRejections != 0 || !finalAudioStarted || !finalVideoStarted
                || finalStreamingStarts.get() != 1 || firstVideoMillis < 0L
                || finalVideoMillis < firstVideoMillis + VIDEO_ADVANCE_MILLIS
                || !closeStarted || !converged || !resourcesConverged(context)) {
            throw new AssertionError("Streaming-channel recovery evidence incomplete: " + describe(context));
        }

        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        if (tap.instancesCreated() != tapBaseline.instancesCreated() + 1L
                || tap.closesCompleted() != tapBaseline.closesCompleted() + 1L
                || stereo.instancesCreated() != stereoBaseline.instancesCreated() + 1L
                || stereo.cleanupsStarted() != stereoBaseline.cleanupsStarted() + 1L
                || stereo.cleanupsCompleted() != stereoBaseline.cleanupsCompleted() + 1L) {
            throw new AssertionError("Only the final real MP3 may create an OpenAL pipeline: tap="
                    + tapBaseline + " -> " + tap + ", stereo=" + stereoBaseline + " -> " + stereo);
        }

        VideoCloseDiagnostics.Snapshot close = VideoCloseDiagnostics.global().snapshot(System.nanoTime());
        if (close.retainedCompleted() <= videoCloseBaseline.retainedCompleted()
                || close.hardTimeouts() != videoCloseBaseline.hardTimeouts()
                || close.lateConvergences() != videoCloseBaseline.lateConvergences()
                || close.failedConvergences() != videoCloseBaseline.failedConvergences()
                || close.droppedOperations() != videoCloseBaseline.droppedOperations()) {
            throw new AssertionError("Final native video did not close cleanly: "
                    + videoCloseBaseline + " -> " + close);
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        for (BenchSound sound : trackedSounds) {
            stopSound(context, sound);
        }
        SyncedMediaSound.tickPendingDecodeAdmissions();
        cancelFinalRequest();
        VideoBillboardPreview.stopIfSession(finalSession.value());
        ModernTurntablePlaybackTracker.stopAllSounds();
        if (listenerRegistered) {
            NeoForge.EVENT_BUS.unregister(streamingListener);
            listenerRegistered = false;
        }
        cleanupGlobalResources();
    }

    private void submitChurnSound(BenchClientContext context) {
        if (churnCompleted >= CHURN_ROUNDS) {
            advance(Phase.FINAL_SUBMIT);
            return;
        }
        PlaybackSessionId session = PlaybackSessionId.of("bench-channel-churn-" + churnCompleted);
        currentChurnSound = new BenchSound(pendingUrl(churnCompleted), session, false, asyncFailure);
        trackedSounds.add(currentChurnSound);
        submitSound(context, currentChurnSound, "churn " + churnCompleted);
        currentObservedActive = false;
        advance(Phase.CHURN_RESERVED);
    }

    private void stopReservedChurnSound(BenchClientContext context) {
        currentObservedActive |= isActive(context, currentChurnSound);
        if (phaseTicks >= RESERVATION_HOLD_TICKS && currentObservedActive) {
            if (currentChurnSound.streamCreationStartedForBench()) {
                throw new AssertionError("Churn sound opened its decoder before cancellation: round="
                        + churnCompleted);
            }
            stopSound(context, currentChurnSound);
            SyncedMediaSound.tickPendingDecodeAdmissions();
            churnCompleted++;
            advance(Phase.CHURN_RELEASING);
            return;
        }
        if (phaseTicks > RESERVATION_TIMEOUT_TICKS) {
            throw new AssertionError("Churn sound never became active before cancellation: round="
                    + churnCompleted + " result=" + describe(context));
        }
    }

    private void awaitChurnRelease(BenchClientContext context) {
        if (!isActive(context, currentChurnSound) && phaseTicks >= RELEASE_SETTLE_TICKS) {
            currentChurnSound = null;
            advance(Phase.CHURN_SUBMIT);
            return;
        }
        if (phaseTicks > RESERVATION_TIMEOUT_TICKS) {
            throw new AssertionError("Cancelled churn sound stayed active: round=" + churnCompleted
                    + " state=" + describe(context));
        }
    }

    private void submitFinalMedia(BenchClientContext context) {
        if (video == null) {
            throw new AssertionError("Final video was not resolved");
        }
        try {
            PlaybackRequest request = PlaybackRequest.now(audioProperties.url(), null, finalSession.value(), 0L,
                    TOTAL_MILLIS, ownerId, null);
            finalRequest = HttpAudioStreamHandler.registerRequest(request);
            finalSound = new BenchSound(URI.create(finalRequest.url()).toURL(), finalSession, true, asyncFailure);
        } catch (Exception error) {
            cancelFinalRequest();
            throw new AssertionError("Could not construct the final real MP3 sound", error);
        }
        trackedSounds.add(finalSound);
        submitSound(context, finalSound, "final real MP3");
        VideoBillboardPreview.startRgbaPreviewAt(video.url(), video.width(), video.height(), video.fps(),
                video.codecId(), finalSession.value(), 0L, video.durationMillis(), true, null);
        VideoBillboardPreview.pumpPreviewFrame(finalSession.value());
        advance(Phase.FINAL_LOADING);
    }

    private void awaitFinalMedia(BenchClientContext context) {
        // World geometry submission marks a projector-less instance offscreen. Reassert the explicit
        // preview consumer before pumping, exactly as the production preview Screen does each frame.
        VideoBillboardPreview.pumpPreviewFrame(finalSession.value());
        StereoOpenALHandler.DiagnosticSnapshot output = ClientAudioOutputRegistry
                .getSessionStereoSnapshot(finalSession).orElse(null);
        VideoBillboardPreview.BenchDecoderState state =
                VideoBillboardPreview.benchDecoderState(finalSession.value());
        if (VideoBillboardPreview.hasTerminalFailure(finalSession.value())) {
            throw new AssertionError("Final video reached a terminal decoder failure: " + describe(context));
        }
        boolean audioReady = finalSound.streamReady() && finalStreamingStarts.get() == 1
                && isActive(context, finalSound) && output != null && output.started()
                && output.firstPcm().samples() >= 1_024L;
        boolean videoReady = state.present() && state.hasFrame()
                && VideoBillboardPreview.isSessionRunning(finalSession.value());
        if (audioReady && videoReady) {
            requirePcmQuality("post-churn final SoundEngine channel", output.firstPcm());
            finalAudioStarted = true;
            firstVideoMillis = VideoBillboardPreview.getSyncStatus(finalSession.value()).mediaMillis();
            if (firstVideoMillis < 0L) {
                throw new AssertionError("Final native video has a frame but no media clock: " + describe(context));
            }
            advance(Phase.FINAL_ADVANCING);
            return;
        }
        if (phaseTicks > 1_200) {
            throw new AssertionError("Final audio/video did not start after channel churn: " + describe(context));
        }
    }

    private void awaitVideoAdvance(BenchClientContext context) {
        VideoBillboardPreview.pumpPreviewFrame(finalSession.value());
        VideoBillboardPreview.BenchDecoderState state =
                VideoBillboardPreview.benchDecoderState(finalSession.value());
        long mediaMillis = VideoBillboardPreview.getSyncStatus(finalSession.value()).mediaMillis();
        if (state.present() && state.hasFrame() && VideoBillboardPreview.isSessionRunning(finalSession.value())
                && mediaMillis >= firstVideoMillis + VIDEO_ADVANCE_MILLIS) {
            finalVideoMillis = mediaMillis;
            finalVideoStarted = true;
            beginClose(context);
            advance(Phase.CLOSING);
            return;
        }
        if (phaseTicks > 200) {
            throw new AssertionError("Final native video frame did not advance: " + describe(context));
        }
    }

    private void beginClose(BenchClientContext context) {
        closeStarted = true;
        stopSound(context, finalSound);
        cancelFinalRequest();
        VideoBillboardPreview.stopIfSession(finalSession.value());
    }

    private void submitSound(BenchClientContext context, BenchSound sound, String label) {
        playAttempts++;
        SoundEngine.PlayResult result = context.minecraft().getSoundManager().play(sound);
        if (result == SoundEngine.PlayResult.NOT_STARTED) {
            playRejections++;
            sound.requestStop();
            throw new AssertionError("Minecraft exhausted its streaming-channel pool while submitting " + label
                    + ": attempts=" + playAttempts + " completedChurn=" + churnCompleted);
        }
    }

    private ResolvedVideo resolveVideo() {
        try {
            BiliApiClient.VideoId videoId = BiliApiClient.extractVideoId(videoProperties.videoId());
            if (videoId == null) {
                throw new IllegalArgumentException("invalid Bilibili video id " + videoProperties.videoId());
            }
            BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(videoId);
            BiliApiClient.VideoStreamPlan plan = BiliApiClient.getVideoStreamPlan(
                    videoId, info.cid(), videoProperties.quality());
            BiliApiClient.VideoStream stream = !plan.h264Candidates().isEmpty()
                    ? plan.h264Candidates().getFirst() : plan.preferred();
            if (stream.baseUrl() == null || stream.baseUrl().isBlank()) {
                throw new IllegalStateException("Bilibili returned an empty video URL");
            }
            return new ResolvedVideo(stream.baseUrl(), stream.codecId(), Math.max(1, stream.width()),
                    Math.max(1, stream.height()), BiliVideoStreamResolver.parseFrameRate(stream.frameRate(), 30),
                    Math.max(1L, info.duration() * 1_000L));
        } catch (Exception error) {
            throw new CompletionException(error);
        }
    }

    private void captureBaseline() {
        tapBaseline = OpenALTappedAudioInputStream.lifecycleSnapshot();
        stereoBaseline = StereoOpenALHandler.lifecycleSnapshot();
        videoCloseBaseline = VideoCloseDiagnostics.global().snapshot(System.nanoTime());
        audioCloseBaseline = AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
        httpActiveBaseline = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests();
        pendingNativeDeleteBaseline = OpenALSpatialAudio.pendingNativeDeleteBatches();
        memoryBaseline = RealMediaLifecycleScenario.currentOwnedBytesByCategory();
        baselineCaptured = true;
    }

    private boolean idle() {
        VideoBillboardPreview.ResourceDiagnostics resources = VideoBillboardPreview.resourceDiagnostics();
        return resources.instances() == 0 && resources.pendingLoading() == 0 && resources.activeCloseZombies() == 0
                && OpenALTappedAudioInputStream.lifecycleSnapshot().activeInstances() == 0
                && StereoOpenALHandler.lifecycleSnapshot().activeInstances() == 0
                && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                && HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() == 0
                && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                && ClientAudioOutputRegistry.getSessionStereoSnapshot(finalSession).isEmpty()
                && RealMediaLifecycleScenario.currentOwnedBytes() == 0L;
    }

    private boolean resourcesConverged(BenchClientContext context) {
        if (!baselineCaptured || trackedSounds.stream().anyMatch(sound -> isActive(context, sound))) {
            return false;
        }
        VideoBillboardPreview.ResourceDiagnostics resources = VideoBillboardPreview.resourceDiagnostics();
        return !VideoBillboardPreview.isSessionRunning(finalSession.value())
                && resources.instances() == 0 && resources.pendingLoading() == 0
                && resources.activeCloseZombies() == 0
                && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations()
                        <= videoCloseBaseline.activeOperations()
                && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations()
                        <= audioCloseBaseline
                && HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests()
                        <= httpActiveBaseline
                && OpenALSpatialAudio.pendingNativeDeleteBatches() <= pendingNativeDeleteBaseline
                && OpenALTappedAudioInputStream.lifecycleSnapshot().activeInstances()
                        == tapBaseline.activeInstances()
                && StereoOpenALHandler.lifecycleSnapshot().activeInstances()
                        == stereoBaseline.activeInstances()
                && ClientAudioOutputRegistry.getSessionStereoSnapshot(finalSession).isEmpty()
                && Arrays.equals(memoryBaseline, RealMediaLifecycleScenario.currentOwnedBytesByCategory());
    }

    private void record(BenchClientContext context) {
        VideoBillboardPreview.BenchDecoderState videoState =
                VideoBillboardPreview.benchDecoderState(finalSession.value());
        context.metrics().record(CHURN_COMPLETED, churnCompleted);
        context.metrics().record(PLAY_REJECTIONS, playRejections);
        context.metrics().record(STREAMING_STARTS, streamingStarts.get());
        context.metrics().record(FINAL_STREAMING_STARTS, finalStreamingStarts.get());
        context.metrics().record(VIDEO_HAS_FRAME, videoState.hasFrame() ? 1L : 0L);
        context.metrics().record(VIDEO_CLOSE_ACTIVE,
                VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations());
        context.metrics().record(VIDEO_CLOSE_ZOMBIES,
                VideoBillboardPreview.resourceDiagnostics().activeCloseZombies());
        context.metrics().record(OWNED_BYTES, RealMediaLifecycleScenario.currentOwnedBytes());
    }

    private void tickClosures() {
        VideoCloseDiagnostics.tickGlobal();
        AudioNativeCloseDiagnostics.tickGlobal();
        OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
    }

    private void cleanupGlobalResources() {
        ModernTurntableVideoClient.clear();
        VideoBillboardPreview.stopIfSession(finalSession.value());
        VideoBillboardPreview.stop();
        VideoBillboardPreview.releaseBenchUploadResources();
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
        tickClosures();
    }

    private void cancelFinalRequest() {
        if (finalRequest != null) {
            finalRequest.requestToken().ifPresent(HttpAudioStreamHandler::cancelRequest);
            finalRequest = null;
        }
    }

    private void throwIfFailed() {
        Throwable resolutionError = resolutionFailure.get();
        if (resolutionError != null) {
            throw new AssertionError("Failed to resolve the final real Bilibili video", resolutionError);
        }
        Throwable error = asyncFailure.get();
        if (error != null) {
            throw new AssertionError("Streaming-channel recovery media failed", error);
        }
    }

    private String describe(BenchClientContext context) {
        StereoOpenALHandler.DiagnosticSnapshot output = ownerId != null
                ? ClientAudioOutputRegistry.getSessionStereoSnapshot(finalSession).orElse(null) : null;
        return "phase=" + phase + " phaseTicks=" + phaseTicks + " churn=" + churnCompleted + '/'
                + CHURN_ROUNDS + " attempts=" + playAttempts + " rejected=" + playRejections
                + " streaming=" + streamingStarts + " finalStreaming=" + finalStreamingStarts
                + " finalActive=" + isActive(context, finalSound) + " output=" + output + " video="
                + VideoBillboardPreview.benchDecoderState(finalSession.value()) + " resources="
                + VideoBillboardPreview.resourceDiagnostics() + " videoClose="
                + VideoCloseDiagnostics.global().snapshot(System.nanoTime()) + " audioClose="
                + AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()) + " http="
                + HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()) + " memory="
                + Arrays.toString(RealMediaLifecycleScenario.currentOwnedBytesByCategory());
    }

    private void advance(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private static URL pendingUrl(int round) {
        try {
            return URI.create("http://127.0.0.1:9/ncpb-channel-churn-" + round + ".mp3").toURL();
        } catch (Exception error) {
            throw new IllegalStateException("Could not construct pending churn URL", error);
        }
    }

    private static void stopSound(BenchClientContext context, BenchSound sound) {
        if (sound != null) {
            context.minecraft().getSoundManager().stop(sound);
            sound.requestStop();
        }
    }

    private static boolean isActive(BenchClientContext context, BenchSound sound) {
        return sound != null && context.minecraft().getSoundManager().isActive(sound);
    }

    private enum Phase {
        CHURN_SUBMIT,
        CHURN_RESERVED,
        CHURN_RELEASING,
        FINAL_SUBMIT,
        FINAL_LOADING,
        FINAL_ADVANCING,
        CLOSING
    }

    private record ResolvedVideo(String url, int codecId, int width, int height, int fps,
            long durationMillis) {
    }

    private static final class BenchSound extends SyncedMediaSound {
        private final boolean admitDecode;
        private final AtomicReference<Throwable> failure;
        private final AtomicBoolean streamReady = new AtomicBoolean();

        private BenchSound(URL url, PlaybackSessionId sessionId, boolean admitDecode,
                AtomicReference<Throwable> failure) {
            super(url, (int) (TOTAL_MILLIS / 1_000L), null, sessionId.value(), 0L);
            this.admitDecode = admitDecode;
            this.failure = failure;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0.0F;
        }

        @Override
        public void tick() {
            tick++;
        }

        @Override
        protected void refreshDecodeDemand() {
            if (admitDecode) {
                setDecodeDemand(true);
            }
        }

        @Override
        protected void onStreamReady() {
            streamReady.set(true);
        }

        @Override
        protected void onStreamFailure(Exception error) {
            if (admitDecode) {
                failure.compareAndSet(null, error);
            }
        }

        @Override
        protected void finishSession() {
        }

        @Override
        protected String streamDebugName() {
            return admitDecode ? "post-churn real MP3 bench" : "pending channel-churn bench";
        }

        private boolean streamReady() {
            return streamReady.get();
        }

        private boolean streamCreationStartedForBench() {
            return streamCreationStarted();
        }

        private void requestStop() {
            stop();
        }
    }
}
