package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requirePcmQuality;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.github.tartaricacid.netmusic.init.InitItems;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.BiliRealVideoPlaybackBench;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureProperties;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleAudioRoutePolicy;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real Bilibili DASH decode/audio proof, followed by an optional world stage in which a projector and
 * screen-only console linked to a real turntable session leave render range and return. The session must
 * pause rather than be destroyed outside range and resume with advanced media time on re-entry.
 * Disable the world stage with {@code -Dncpb.bench.real_bv.range_reentry=false}.
 */
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
    private static final boolean RANGE_REENTRY = Boolean.parseBoolean(
            System.getProperty("ncpb.bench.real_bv.range_reentry", "true"));

    private final AtomicReference<Throwable> audioResolutionFailure = new AtomicReference<>();
    private CompletableFuture<ResolvedAudio> audioResolution;
    private ResolvedAudio resolvedAudio;
    private RealMediaStages.RealAudioStage audio;
    private PlaybackSessionId audioSession;
    private UUID audioOwner;
    private BiliRealVideoPlaybackBench.RunSnapshot finalSnapshot;
    private StereoOpenALHandler.PcmQuality decodedAudioPcm =
            new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D);
    private long decodedAudioInputSamples;
    private boolean audioDecoded;
    private boolean cleanupRequested;
    private RangeReentryStage rangeStage;

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
                                RealMediaStages.unwrapCompletion(error));
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
            audio = RealMediaStages.RealAudioStage.start(
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
        if (rangeStage != null && !rangeStage.done()) {
            if (rangeStage.step(context)) {
                cleanupMedia();
            }
            return BenchClientStepResult.CONTINUE;
        }
        if (!resourcesConverged(audio)) {
            return BenchClientStepResult.CONTINUE;
        }
        if (RANGE_REENTRY && rangeStage == null) {
            rangeStage = new RangeReentryStage(context);
            return BenchClientStepResult.CONTINUE;
        }
        return BenchClientStepResult.COMPLETE;
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
        if (RANGE_REENTRY && (rangeStage == null || !rangeStage.done())) {
            throw new AssertionError("Real video range re-entry stage did not complete: " + rangeStage);
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
        if (rangeStage != null) {
            rangeStage.teardown(context);
        }
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

    private static boolean resourcesConverged(RealMediaStages.RealAudioStage audio) {
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

    /** Visible -> out of render/decode range -> visible re-entry on one synchronized video session. */
    private static final class RangeReentryStage {
        private static final int FAR_DISTANCE = 128;
        private static final long MIN_RETURN_ADVANCE_MILLIS = 500L;
        private final VideoFeatureProperties.RealMediaLifecycle properties =
                VideoFeatureProperties.realMediaLifecycle();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean fixtureReady = new AtomicBoolean();
        private final AtomicBoolean cleanupServerDone = new AtomicBoolean();
        private final CompletableFuture<BiliVideoStreamResolver.ResolvedVideoStream> resolution;
        private final UUID playerId;
        private final BlockPos projectorPos;
        private final BlockPos consolePos;
        private final BlockPos sourcePos;
        private final Vec3 home;
        private final Vec3 far;
        private String sessionId = "";
        private int closeBaseline;
        private int httpBaseline;
        private int phase;
        private int phaseTicks;
        private long firstMediaMillis = -1L;
        private long returnedMediaMillis = -1L;
        private boolean outsidePaused;
        private boolean cleanupRequested;
        private boolean done;

        RangeReentryStage(BenchClientContext context) {
            ModernTurntablePlaybackTracker.stopAllSounds();
            playerId = context.player().getUUID();
            context.player().setNoGravity(true);
            home = context.player().position();
            far = home.add(0.0D, 0.0D, -FAR_DISTANCE);
            projectorPos = context.player().blockPosition().offset(0, 0, 4).immutable();
            consolePos = projectorPos.offset(-2, 0, 0).immutable();
            sourcePos = projectorPos.offset(2, 0, 0).immutable();
            resolution = CompletableFuture.supplyAsync(() -> {
                try {
                    return BiliVideoStreamResolver.resolve(properties.videoId(), properties.quality(), 30);
                } catch (Exception error) {
                    throw new CompletionException(error);
                }
            });
            submitServer(context, (player, level) -> {
                player.setNoGravity(true);
                for (BlockPos pos : List.of(projectorPos, consolePos, sourcePos)) {
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
                }
                level.setBlockAndUpdate(sourcePos, ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());
                level.setBlockAndUpdate(projectorPos, ModBlocks.VIDEO_PROJECTOR.get().defaultBlockState());
                level.setBlockAndUpdate(consolePos, ModBlocks.CONTROL_CONSOLE.get().defaultBlockState());
                if (!(level.getBlockEntity(sourcePos) instanceof ModernTurntableBlockEntity turntable)
                        || !(level.getBlockEntity(projectorPos) instanceof VideoProjectorBlockEntity projector)
                        || !(level.getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity console)) {
                    throw new IllegalStateException("Video range re-entry fixtures were not created");
                }
                projector.setPreferredQuality(properties.quality());
                projector.linkTo(sourcePos);
                console.linkTo(level.dimension().identifier().toString(), sourcePos,
                        ControlConsoleDocument.SourceKind.TURNTABLE);
                turntable.setVolumePerMille(1_000);
                turntable.setDisc(ItemMusicCD.setSongInfo(new ItemMusicCD.SongInfo(
                        AudioStreamProperties.realMp3Bench().url(), "real video range re-entry clock", 360, false),
                        new ItemStack(InitItems.MUSIC_CD.get())));
                turntable.startFromDisc(player);
                teleport(player, level, home);
                fixtureReady.set(true);
            });
        }

        /** Returns true exactly once, when the stage has converged and the caller should run global cleanup. */
        boolean step(BenchClientContext context) {
            throwIfFailed();
            phaseTicks++;
            VideoBillboardPreview.VideoSyncStatus sync = VideoBillboardPreview.getSyncStatus(sessionId);
            VideoBillboardPreview.BenchDecoderState state = VideoBillboardPreview.benchDecoderState(sessionId);
            StereoOpenALHandler.DiagnosticSnapshot audio =
                    ClientAudioOutputRegistry.getStereoSnapshot(sourcePos).orElse(null);
            switch (phase) {
                case 0 -> startWhenLinked(context);
                case 1 -> {
                    if (state.present() && state.prewarmVisible() && state.hasFrame()
                            && VideoBillboardPreview.currentProjectorFrame(projectorPos).hasFrame()
                            && sync.mediaMillis() >= 0L && healthyAudio(audio)) {
                        requirePcmQuality("screen-only console initial audio", audio.firstPcm());
                        firstMediaMillis = sync.mediaMillis();
                        submitServer(context, (player, level) -> teleport(player, level, far));
                        advance(2);
                    }
                }
                case 2 -> {
                    if (context.player().position().distanceToSqr(far) >= 4.0D) {
                        break;
                    }
                    if (!state.present() || !VideoBillboardPreview.isSessionRunning(sessionId)
                            || VideoBillboardPreview.resourceDiagnostics().instances() != 1) {
                        throw new AssertionError("Video session was destroyed outside range: " + describe(state, sync));
                    }
                    if (state.offscreenPauseActive() && audio == null) {
                        outsidePaused = true;
                        submitServer(context, (player, level) -> teleport(player, level, home));
                        advance(3);
                    }
                }
                case 3 -> {
                    if (!state.present() || !VideoBillboardPreview.isSessionRunning(sessionId)) {
                        throw new AssertionError("Video session disappeared before visible re-entry: "
                                + describe(state, sync));
                    }
                    if (context.player().position().distanceToSqr(home) < 4.0D
                            && state.prewarmVisible() && state.hasFrame()
                            && sync.mediaMillis() >= firstMediaMillis + MIN_RETURN_ADVANCE_MILLIS
                            && healthyAudio(audio)) {
                        requirePcmQuality("screen-only console returned audio", audio.firstPcm());
                        returnedMediaMillis = sync.mediaMillis();
                        VideoBillboardPreview.stopIfSession(sessionId);
                        requestCleanup(context);
                        advance(4);
                    }
                }
                case 4 -> {
                    if (cleanupServerDone.get() && converged()) {
                        ModernTurntablePlaybackTracker.stopAllSounds();
                        context.player().setNoGravity(false);
                        done = true;
                        return true;
                    }
                }
                default -> throw new AssertionError("Unexpected real video range re-entry phase " + phase);
            }
            if ((phase == 3 && phaseTicks > 240) || phaseTicks > 1_200) {
                throw new AssertionError("Real video range re-entry timed out: " + describe(state, sync));
            }
            return false;
        }

        private void startWhenLinked(BenchClientContext context) {
            if (!fixtureReady.get() || !resolution.isDone()
                    || !(context.level().getBlockEntity(projectorPos) instanceof VideoProjectorBlockEntity projector)
                    || !(context.level().getBlockEntity(sourcePos) instanceof ModernTurntableBlockEntity turntable)
                    || !(context.level().getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity console)
                    || !sourcePos.equals(projector.getLinkedTurntablePos())
                    || !ControlConsoleAudioRoutePolicy.takesOverMainOutput(console.document())
                    || !turntable.isPlaying()
                    || VideoBillboardPreview.resourceDiagnostics().instances() != 0
                    || VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != 0) {
                return;
            }
            sessionId = turntable.getPlaybackSyncMetadata().sessionId();
            if (sessionId.isBlank()) {
                return;
            }
            BiliVideoStreamResolver.ResolvedVideoStream stream = resolution.join();
            closeBaseline = VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
            httpBaseline = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests();
            VideoBillboardPreview.startSyncedCandidates(
                    stream.candidates(), stream.sourceWidth(), stream.sourceHeight(), stream.fps(),
                    sessionId, 0L, 0L, List.of(projectorPos, consolePos), sourcePos, true, null);
            advance(1);
        }

        boolean done() {
            return done && outsidePaused && returnedMediaMillis >= firstMediaMillis + MIN_RETURN_ADVANCE_MILLIS;
        }

        void teardown(BenchClientContext context) {
            VideoBillboardPreview.stopIfSession(sessionId);
            ModernTurntablePlaybackTracker.stopAllSounds();
            context.player().setNoGravity(false);
            if (!cleanupRequested) {
                requestCleanup(context);
            }
        }

        private void requestCleanup(BenchClientContext context) {
            cleanupRequested = true;
            submitServer(context, (player, level) -> {
                if (level.getBlockEntity(sourcePos) instanceof ModernTurntableBlockEntity turntable) {
                    turntable.stopPlayback();
                }
                for (BlockPos pos : List.of(projectorPos, consolePos, sourcePos)) {
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(pos.below(), Blocks.AIR.defaultBlockState());
                }
                player.setNoGravity(false);
                teleport(player, level, home);
                cleanupServerDone.set(true);
            });
        }

        private boolean converged() {
            return !VideoBillboardPreview.isSessionRunning(sessionId)
                    && VideoBillboardPreview.resourceDiagnostics().instances() == 0
                    && VideoBillboardPreview.resourceDiagnostics().activeCloseZombies() == 0
                    && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() <= closeBaseline
                    && HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests()
                            <= httpBaseline;
        }

        private static boolean healthyAudio(StereoOpenALHandler.DiagnosticSnapshot audio) {
            return audio != null && audio.started() && audio.firstPcm().samples() >= 1_024L;
        }

        private void submitServer(BenchClientContext context, ServerAction action) {
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
                    action.run(player, level);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
        }

        private static void teleport(ServerPlayer player, ServerLevel level, Vec3 target) {
            if (!player.teleportTo(level, target.x, target.y, target.z, Set.<Relative>of(), 0.0F, 0.0F, true)) {
                throw new IllegalStateException("Could not teleport video range re-entry player to " + target);
            }
        }

        private void advance(int nextPhase) {
            phase = nextPhase;
            phaseTicks = 0;
        }

        private void throwIfFailed() {
            if (resolution.isCompletedExceptionally()) {
                resolution.exceptionally(error -> {
                    failure.compareAndSet(null, RealMediaStages.unwrapCompletion(error));
                    return null;
                });
            }
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Real video range re-entry failed", error);
            }
        }

        private String describe(VideoBillboardPreview.BenchDecoderState state,
                VideoBillboardPreview.VideoSyncStatus sync) {
            return "phase=" + phase + " state=" + state + " sync=" + sync + " resources="
                    + VideoBillboardPreview.resourceDiagnostics() + " close="
                    + VideoCloseDiagnostics.global().snapshot(System.nanoTime()) + " http="
                    + HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()) + " audioDemand="
                    + ClientAudioOutputRegistry.audioDemandDebug(sourcePos, null, sessionId);
        }

        @Override
        public String toString() {
            return "RangeReentryStage[phase=" + phase + ", session=" + sessionId + ", first=" + firstMediaMillis
                    + ", returned=" + returnedMediaMillis + ", outsidePaused=" + outsidePaused + ", done=" + done
                    + ']';
        }

        @FunctionalInterface
        private interface ServerAction {
            void run(ServerPlayer player, ServerLevel level) throws Exception;
        }
    }
}
