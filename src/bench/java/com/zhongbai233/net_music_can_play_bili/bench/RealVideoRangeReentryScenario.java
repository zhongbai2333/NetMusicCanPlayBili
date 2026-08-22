package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.github.tartaricacid.netmusic.init.InitItems;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureProperties;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleAudioRoutePolicy;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real production video proof for visible -> out of render/decode range -> visible
 * re-entry. The old range-removal path fails this scenario because it disposes
 * the only session instance while the synchronized session is still active.
 */
final class RealVideoRangeReentryScenario implements BenchClientScenario {
    private String sessionId = "";
    private static final int FAR_DISTANCE = 128;
    private static final long MIN_RETURN_ADVANCE_MILLIS = 500L;
    private static final BenchMetricDescriptor MEDIA_MILLIS = new BenchMetricDescriptor(
            "ncpb.real_video_range_reentry.media_millis", "milliseconds", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor GENERATION = new BenchMetricDescriptor(
            "ncpb.real_video_range_reentry.generation", "generation", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor INSTANCES = new BenchMetricDescriptor(
            "ncpb.real_video_range_reentry.instances", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor PHASE = new BenchMetricDescriptor(
            "ncpb.real_video_range_reentry.phase", "phase", MetricDirection.NEUTRAL);

    private final VideoFeatureProperties.RealMediaLifecycle properties =
            VideoFeatureProperties.realMediaLifecycle();
    private final AudioStreamProperties.RealMp3Bench audioProperties =
            AudioStreamProperties.realMp3Bench();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<Throwable> resolutionFailure = new AtomicReference<>();
    private final AtomicBoolean fixtureReady = new AtomicBoolean();
    private final AtomicBoolean cleanupServerDone = new AtomicBoolean();
    private CompletableFuture<BiliVideoStreamResolver.ResolvedVideoStream> resolution;
    private BiliVideoStreamResolver.ResolvedVideoStream stream;
    private UUID playerId;
    private BlockPos projectorPos;
    private BlockPos consolePos;
    private BlockPos sourcePos;
    private Vec3 home;
    private Vec3 far;
    private int closeBaseline;
    private int httpBaseline;
    private int phase;
    private int phaseTicks;
    private long firstMediaMillis = -1L;
    private long returnedMediaMillis = -1L;
    private long firstGeneration = -1L;
    private boolean started;
    private boolean pauseObserved;
    private boolean audioRetired;
    private boolean audioReturned;
    private boolean returnObserved;
    private boolean farTeleportRequested;
    private boolean homeTeleportRequested;
    private boolean cleanupRequested;

    @Override
    public void setup(BenchClientContext context) {
        ModernTurntableVideoClient.clear();
        VideoBillboardPreview.stop();
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
        ModernTurntablePlaybackTracker.stopAllSounds();
        playerId = context.player().getUUID();
        context.player().setNoGravity(true);
        home = context.player().position();
        far = home.add(0.0D, 0.0D, -FAR_DISTANCE);
        projectorPos = context.player().blockPosition().offset(0, 0, 4).immutable();
        consolePos = projectorPos.offset(-2, 0, 0).immutable();
        sourcePos = projectorPos.offset(2, 0, 0).immutable();
        resolution = CompletableFuture.supplyAsync(this::resolve)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        resolutionFailure.compareAndSet(null,
                                RealMediaLifecycleScenario.unwrapCompletion(error));
                    }
                });
        submitServer(context, () -> {
            ServerPlayer player = serverPlayer(context);
            ServerLevel level = (ServerLevel) player.level();
            player.setNoGravity(true);
            level.setBlockAndUpdate(projectorPos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(consolePos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(sourcePos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(projectorPos.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(consolePos.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(sourcePos.below(), Blocks.STONE.defaultBlockState());
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
            if (!ControlConsoleAudioRoutePolicy.takesOverMainOutput(console.document())) {
                throw new IllegalStateException("Linked screen-only console did not own the audio route");
            }
            turntable.setVolumePerMille(1_000);
            turntable.setDisc(realAudioDisc());
            turntable.startFromDisc(player);
            teleport(player, level, home, 0.0F, 0.0F);
            fixtureReady.set(true);
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        tickClosures();
        throwIfFailed();
        if (!fixtureReady.get() || resolution == null || !resolution.isDone()
                || !(context.level().getBlockEntity(projectorPos) instanceof VideoProjectorBlockEntity projector)
                || !(context.level().getBlockEntity(sourcePos) instanceof ModernTurntableBlockEntity turntable)
                || !(context.level().getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity console)
                || !sourcePos.equals(projector.getLinkedTurntablePos())
                || console.document().sourceX() != sourcePos.getX()
                || console.document().sourceY() != sourcePos.getY()
                || console.document().sourceZ() != sourcePos.getZ()
                || !ControlConsoleAudioRoutePolicy.takesOverMainOutput(console.document())
                || !turntable.isPlaying()
                || !context.environment().readiness().ready() || context.frames().sampleCount() < 2
                || !idle()) {
            return BenchClientStepResult.CONTINUE;
        }
        if (stream == null) {
            stream = resolution.join();
        }
        if (sessionId.isBlank()) {
            sessionId = turntable.getPlaybackSyncMetadata().sessionId();
            if (sessionId.isBlank()) {
                return BenchClientStepResult.CONTINUE;
            }
        }
        if (!started) {
            closeBaseline = VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations();
            httpBaseline = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests();
            VideoBillboardPreview.startSyncedCandidates(
                    stream.candidates(), stream.sourceWidth(), stream.sourceHeight(), stream.fps(),
                    sessionId, 0L, 0L, List.of(projectorPos, consolePos), sourcePos, true, null);
            started = true;
        }
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        throwIfFailed();
        record(context);
        VideoBillboardPreview.VideoSyncStatus sync = VideoBillboardPreview.getSyncStatus(sessionId);
        VideoBillboardPreview.BenchDecoderState state = VideoBillboardPreview.benchDecoderState(sessionId);
        StereoOpenALHandler.DiagnosticSnapshot audio = ClientAudioOutputRegistry
                .getStereoSnapshot(sourcePos).orElse(null);
        if (!state.present() || !state.prewarmVisible() || !state.hasFrame()
                || !VideoBillboardPreview.currentProjectorFrame(projectorPos).hasFrame()
                || sync.mediaMillis() < 0L || !healthyAudio(audio)) {
            return BenchClientStepResult.CONTINUE;
        }
        NetMusicBenchProvider.requirePcmQuality("screen-only console initial audio", audio.firstPcm());
        firstMediaMillis = sync.mediaMillis();
        firstGeneration = state.generation();
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        throwIfFailed();
        phaseTicks++;
        tickClosures();
        record(context);
        VideoBillboardPreview.VideoSyncStatus sync = VideoBillboardPreview.getSyncStatus(sessionId);
        VideoBillboardPreview.BenchDecoderState state = VideoBillboardPreview.benchDecoderState(sessionId);
        StereoOpenALHandler.DiagnosticSnapshot audio = ClientAudioOutputRegistry
                .getStereoSnapshot(sourcePos).orElse(null);

        switch (phase) {
            case 0 -> {
                if (!farTeleportRequested) {
                    farTeleportRequested = true;
                    requestTeleport(context, far, 0.0F, 0.0F);
                }
                if (context.player().position().distanceToSqr(far) < 4.0D) {
                    advance(1);
                }
            }
            case 1 -> {
                if (!state.present() || !VideoBillboardPreview.isSessionRunning(sessionId)
                        || VideoBillboardPreview.resourceDiagnostics().instances() != 1) {
                    throw new AssertionError("Video session was destroyed outside range: " + describe(state, sync));
                }
                if (state.offscreenPauseActive() && audio == null) {
                    pauseObserved = true;
                    audioRetired = true;
                    if (!homeTeleportRequested) {
                        homeTeleportRequested = true;
                        requestTeleport(context, home, 0.0F, 0.0F);
                    }
                    advance(2);
                }
            }
            case 2 -> {
                if (!state.present() || !VideoBillboardPreview.isSessionRunning(sessionId)) {
                    throw new AssertionError("Video session disappeared before visible re-entry: "
                            + describe(state, sync));
                }
                if (context.player().position().distanceToSqr(home) < 4.0D
                        && state.prewarmVisible() && state.hasFrame()
                        && sync.mediaMillis() >= firstMediaMillis + MIN_RETURN_ADVANCE_MILLIS
                        && healthyAudio(audio)) {
                    NetMusicBenchProvider.requirePcmQuality("screen-only console returned audio", audio.firstPcm());
                    audioReturned = true;
                    returnedMediaMillis = sync.mediaMillis();
                    returnObserved = true;
                    VideoBillboardPreview.stopIfSession(sessionId);
                    requestCleanup(context);
                    advance(3);
                }
            }
            case 3 -> {
                if (cleanupServerDone.get() && converged()) {
                    return BenchClientStepResult.COMPLETE;
                }
            }
            default -> throw new AssertionError("Unexpected real video range re-entry phase " + phase);
        }
        if ((phase == 2 && phaseTicks > 240) || phaseTicks > 1_200) {
            throw new AssertionError("Real video range re-entry timed out: " + describe(state, sync));
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        if (!started || !pauseObserved || !audioRetired || !audioReturned || !returnObserved || returnedMediaMillis < firstMediaMillis
                + MIN_RETURN_ADVANCE_MILLIS || firstGeneration < 0L || !converged()) {
            throw new AssertionError("Real video range re-entry evidence incomplete: first="
                    + firstMediaMillis + " returned=" + returnedMediaMillis + " generation=" + firstGeneration
                    + " pause=" + pauseObserved + " audioRetired=" + audioRetired
                    + " audioReturned=" + audioReturned + " returned=" + returnObserved + " resources="
                    + VideoBillboardPreview.resourceDiagnostics());
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        ModernTurntableVideoClient.clear();
        VideoBillboardPreview.stopIfSession(sessionId);
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
        ModernTurntablePlaybackTracker.stopAllSounds();
        context.player().setNoGravity(false);
        if (!cleanupRequested) {
            requestCleanup(context);
        }
    }

    private ItemStack realAudioDisc() {
        ItemStack stack = new ItemStack(InitItems.MUSIC_CD.get());
        return ItemMusicCD.setSongInfo(new ItemMusicCD.SongInfo(
                audioProperties.url(), "real video range re-entry clock", 360, false), stack);
    }

    private static boolean healthyAudio(StereoOpenALHandler.DiagnosticSnapshot audio) {
        return audio != null && audio.started() && audio.firstPcm().samples() >= 1_024L;
    }

    private BiliVideoStreamResolver.ResolvedVideoStream resolve() {
        try {
            return BiliVideoStreamResolver.resolve(properties.videoId(), properties.quality(), 30);
        } catch (Exception error) {
            throw new CompletionException(error);
        }
    }

    private void requestTeleport(BenchClientContext context, Vec3 target, float yaw, float pitch) {
        submitServer(context, () -> {
            ServerPlayer player = serverPlayer(context);
            teleport(player, (ServerLevel) player.level(), target, yaw, pitch);
        });
    }

    private void requestCleanup(BenchClientContext context) {
        cleanupRequested = true;
        submitServer(context, () -> {
            ServerPlayer player = serverPlayer(context);
            ServerLevel level = (ServerLevel) player.level();
            if (level.getBlockEntity(sourcePos) instanceof ModernTurntableBlockEntity turntable) {
                turntable.stopPlayback();
            }
            level.setBlockAndUpdate(projectorPos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(consolePos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(sourcePos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(projectorPos.below(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(consolePos.below(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(sourcePos.below(), Blocks.AIR.defaultBlockState());
            player.setNoGravity(false);
            teleport(player, level, home, 0.0F, 0.0F);
            cleanupServerDone.set(true);
        });
    }

    private void submitServer(BenchClientContext context, ThrowingAction action) {
        var server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            failure.compareAndSet(null, new IllegalStateException("Integrated server is unavailable"));
            return;
        }
        server.execute(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
    }

    private ServerPlayer serverPlayer(BenchClientContext context) {
        var server = context.minecraft().getSingleplayerServer();
        ServerPlayer player = server != null ? server.getPlayerList().getPlayer(playerId) : null;
        if (player == null || !(player.level() instanceof ServerLevel)) {
            throw new IllegalStateException("Integrated server player is unavailable");
        }
        return player;
    }

    private static void teleport(ServerPlayer player, ServerLevel level, Vec3 target, float yaw, float pitch) {
        if (!player.teleportTo(level, target.x, target.y, target.z, Set.<Relative>of(), yaw, pitch, true)) {
            throw new IllegalStateException("Could not teleport video range re-entry player to " + target);
        }
    }

    private void tickClosures() {
        VideoCloseDiagnostics.tickGlobal();
    }

    private boolean idle() {
        return VideoBillboardPreview.resourceDiagnostics().instances() == 0
                && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0;
    }

    private boolean converged() {
        return !VideoBillboardPreview.isSessionRunning(sessionId)
                && VideoBillboardPreview.resourceDiagnostics().instances() == 0
                && VideoBillboardPreview.resourceDiagnostics().activeCloseZombies() == 0
                && VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() <= closeBaseline
                && HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() <= httpBaseline;
    }

    private void record(BenchClientContext context) {
        VideoBillboardPreview.VideoSyncStatus sync = VideoBillboardPreview.getSyncStatus(sessionId);
        VideoBillboardPreview.BenchDecoderState state = VideoBillboardPreview.benchDecoderState(sessionId);
        context.metrics().record(MEDIA_MILLIS, Math.max(0L, sync.mediaMillis()));
        context.metrics().record(GENERATION, Math.max(0L, state.generation()));
        context.metrics().record(INSTANCES, VideoBillboardPreview.resourceDiagnostics().instances());
        context.metrics().record(PHASE, phase);
    }

    private void advance(int nextPhase) {
        phase = nextPhase;
        phaseTicks = 0;
    }

    private String describe(VideoBillboardPreview.BenchDecoderState state,
            VideoBillboardPreview.VideoSyncStatus sync) {
        return "phase=" + phase + " player=" + MinecraftPosition.of(home, far)
                + " state=" + state + " sync=" + sync + " resources="
                + VideoBillboardPreview.resourceDiagnostics() + " close="
                + VideoCloseDiagnostics.global().snapshot(System.nanoTime()) + " http="
                + HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()) + " audioDemand="
                + ClientAudioOutputRegistry.audioDemandDebug(sourcePos, null, sessionId);
    }

    private void throwIfFailed() {
        Throwable resolutionError = resolutionFailure.get();
        if (resolutionError != null) {
            throw new AssertionError("Real Bilibili video resolution failed", resolutionError);
        }
        Throwable scenarioError = failure.get();
        if (scenarioError != null) {
            throw new AssertionError("Real video range re-entry failed", scenarioError);
        }
    }

    private record MinecraftPosition(Vec3 home, Vec3 far) {
        static MinecraftPosition of(Vec3 home, Vec3 far) {
            return new MinecraftPosition(home, far);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
