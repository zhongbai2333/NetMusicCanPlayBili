package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requirePcmQuality;

import com.github.tartaricacid.netmusic.init.InitItems;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.client.gui.BenchGuiSelector;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientPose;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.bench.api.neoforge.client.BenchGuiSession;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.block.ModernTurntableBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioEndpointIndex;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntableSound;
import com.zhongbai233.net_music_can_play_bili.gui.ModernTurntableScreen;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Production-path proof for place turntable -> drag its real volume slider -> walk out of range ->
 * walk back into range. Player movement deliberately goes through BenchMod automation instead of
 * writing NCPB's cached listener coordinates.
 */
final class RealTurntableVolumeRangeReentryScenario implements BenchClientScenario {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_PHASE_TICKS = 2_400;
    private static final double MOVE_STEP = 4.0D;
    private static final double FAR_DISTANCE = 64.0D;
    private static final int EXPECTED_VOLUME_MIN = 500;
    private static final int EXPECTED_VOLUME_MAX = 620;
    private static final BenchGuiSelector VOLUME_SLIDER = new BenchGuiSelector(
            "", "", "", ModernTurntableScreen.class.getName() + "$VolumeSlider", true, true, null);
    private static final BenchMetricDescriptor CHANNEL_STARTS = new BenchMetricDescriptor(
            "ncpb.real_turntable_volume_reentry.channel_starts", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor VOLUME_PER_MILLE = new BenchMetricDescriptor(
            "ncpb.real_turntable_volume_reentry.volume_per_mille", "per_mille", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor PHASE = new BenchMetricDescriptor(
            "ncpb.real_turntable_volume_reentry.phase", "phase", MetricDirection.NEUTRAL);

    private final AudioStreamProperties.RealMp3Bench properties = AudioStreamProperties.realMp3Bench();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean serverTaskPending = new AtomicBoolean();
    private final AtomicBoolean setupComplete = new AtomicBoolean();
    private final AtomicBoolean handReady = new AtomicBoolean();
    private final AtomicBoolean serverPlaybackObserved = new AtomicBoolean();
    private final AtomicBoolean serverFarObserved = new AtomicBoolean();
    private final AtomicBoolean serverHomeObserved = new AtomicBoolean();
    private final AtomicBoolean serverEjectObserved = new AtomicBoolean();
    private final AtomicInteger serverVolumePerMille = new AtomicInteger(-1);
    private final AtomicReference<String> serverSession = new AtomicReference<>("");
    private final AtomicReference<String> lastObservation = new AtomicReference<>("not started");
    private final AtomicReference<ModernTurntableSound> latestSound = new AtomicReference<>();
    private final AtomicInteger streamingStarts = new AtomicInteger();
    private final Consumer<PlayStreamingSourceEvent> streamingListener = event -> {
        if (event.getSound() instanceof ModernTurntableSound sound) {
            latestSound.set(sound);
            streamingStarts.incrementAndGet();
        }
    };

    private OpenALTappedAudioInputStream.LifecycleSnapshot tapBaseline;
    private StereoOpenALHandler.LifecycleSnapshot stereoBaseline;
    private long audioStagingBaseline;
    private StereoOpenALHandler.PcmQuality firstPcm = emptyPcm();
    private StereoOpenALHandler.PcmQuality secondPcm = emptyPcm();
    private BlockPos turntablePos;
    private BenchClientPose homePose;
    private BenchClientPose farPose;
    private ItemStack pendingHand = ItemStack.EMPTY;
    private UUID playerId;
    private BenchGuiSession gui;
    private long guiOpenedAtFrame;
    private String initialSliderLabel = "";
    private String adjustedSliderLabel = "";
    private boolean listenerRegistered;
    private boolean converged;
    private int phase;
    private int phaseTicks;

    @Override
    public void setup(BenchClientContext context) {
        ClientAudioOutputRegistry.cleanup();
        ClientAudioEndpointIndex.clear();
        HttpAudioStreamHandler.closeModernStreams();
        ModernTurntablePlaybackTracker.stopAllSounds();
        playerId = context.player().getUUID();
        homePose = context.automation().pose();
        farPose = new BenchClientPose(homePose.x(), homePose.y(), homePose.z() + FAR_DISTANCE,
                homePose.yaw(), homePose.pitch());
        turntablePos = context.player().blockPosition().offset(2, 0, 2).immutable();
        tapBaseline = OpenALTappedAudioInputStream.lifecycleSnapshot();
        stereoBaseline = StereoOpenALHandler.lifecycleSnapshot();
        audioStagingBaseline = MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING)
                .currentBytes();
        context.player().setNoGravity(true);
        context.automation().stopMovement();
        NeoForge.EVENT_BUS.addListener(PlayStreamingSourceEvent.class, streamingListener);
        listenerRegistered = true;
        submitServer(context, (level, player) -> {
            player.setNoGravity(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            level.setBlockAndUpdate(turntablePos, ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());
            turntable(level).setVolumePerMille(1_000);
            setupComplete.set(true);
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        throwIfFailed();
        return setupComplete.get() && clientTurntable(context) != null
                && context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        throwIfFailed();
        phaseTicks++;
        OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
        StereoOpenALHandler.DiagnosticSnapshot output = ClientAudioOutputRegistry
                .getStereoSnapshot(turntablePos).orElse(null);
        context.metrics().record(CHANNEL_STARTS, streamingStarts.get());
        context.metrics().record(VOLUME_PER_MILLE, Math.max(0, serverVolumePerMille.get()));
        context.metrics().record(PHASE, phase);

        switch (phase) {
            case 0 -> openTurntableScreen(context);
            case 1 -> dragVolumeSlider(context);
            case 2 -> waitForVolumeSyncAndPrepareDisc(context);
            case 3 -> insertDiscWhenReady(context);
            case 4 -> waitForInitialAudio(context, output);
            case 5 -> moveAndConfirm(context, farPose, serverFarObserved, 6, "player outside range");
            case 6 -> waitOutsideAndReturn(context);
            case 7 -> moveAndConfirm(context, homePose, serverHomeObserved, 8, "player returned home");
            case 8 -> waitForReturnedAudio(context, output);
            case 9 -> ejectWhenReady(context);
            case 10 -> waitForFinalConvergence(context);
            default -> throw new AssertionError("Unexpected real turntable volume re-entry phase " + phase);
        }
        if (phaseTicks > MAX_PHASE_TICKS) {
            throw new AssertionError("Real turntable volume re-entry stalled in phase " + phase + ": "
                    + lastObservation.get() + ", localPose=" + context.automation().pose()
                    + ", channels=" + streamingStarts + ", volume=" + serverVolumePerMille
                    + ", output=" + output + ", demand="
                    + ClientAudioOutputRegistry.audioDemandDebug(turntablePos, null, serverSession.get())
                    + ", tap=" + OpenALTappedAudioInputStream.lifecycleSnapshot() + ", stereo="
                    + StereoOpenALHandler.lifecycleSnapshot());
        }
        return converged ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        requirePcmQuality("turntable slider re-entry first audible pass", firstPcm);
        requirePcmQuality("turntable slider re-entry second audible pass", secondPcm);
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        int volume = serverVolumePerMille.get();
        if (!converged || !serverPlaybackObserved.get() || !serverFarObserved.get()
                || !serverHomeObserved.get() || !serverEjectObserved.get()
                || !"100%".equals(initialSliderLabel) || "100%".equals(adjustedSliderLabel)
                || volume < EXPECTED_VOLUME_MIN || volume > EXPECTED_VOLUME_MAX
                || streamingStarts.get() != 2 || !resourcesConverged()
                || tap.instancesCreated() != tapBaseline.instancesCreated() + 2L
                || tap.closesCompleted() < tapBaseline.closesCompleted() + 2L
                || stereo.instancesCreated() != stereoBaseline.instancesCreated() + 2L
                || stereo.cleanupsCompleted() < stereoBaseline.cleanupsCompleted() + 2L) {
            throw new AssertionError("Real turntable slider/range path did not converge exactly: labels="
                    + initialSliderLabel + "->" + adjustedSliderLabel + ", volume=" + volume
                    + ", channels=" + streamingStarts + ", session=" + serverSession
                    + ", far=" + serverFarObserved + ", home=" + serverHomeObserved
                    + ", tapBaseline=" + tapBaseline + ", tap=" + tap
                    + ", stereoBaseline=" + stereoBaseline + ", stereo=" + stereo);
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        closeGui(context);
        context.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        context.player().setNoGravity(false);
        context.automation().setPose(homePose);
        context.automation().stopMovement();
        ModernTurntablePlaybackTracker.stopAllSounds();
        if (listenerRegistered) {
            NeoForge.EVENT_BUS.unregister(streamingListener);
            listenerRegistered = false;
        }
        ClientAudioEndpointIndex.clear();
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
        var server = context.minecraft().getSingleplayerServer();
        if (server != null && turntablePos != null) {
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && player.level() instanceof ServerLevel level) {
                    player.setNoGravity(false);
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    level.getEntitiesOfClass(ItemEntity.class, new AABB(turntablePos).inflate(3.0D))
                            .forEach(ItemEntity::discard);
                    level.setBlockAndUpdate(turntablePos, Blocks.AIR.defaultBlockState());
                }
            });
        }
    }

    private void openTurntableScreen(BenchClientContext context) {
        if (context.minecraft().screen != null) {
            return;
        }
        context.minecraft().setScreen(new ModernTurntableScreen(turntablePos));
        gui = context.automation().beginGuiSession(ModernTurntableScreen.class);
        guiOpenedAtFrame = context.frames().sampleCount();
        advanceTo(1, "production turntable GUI opened for BenchMod widget automation");
    }

    private void dragVolumeSlider(BenchClientContext context) {
        if (context.frames().sampleCount() <= guiOpenedAtFrame || gui == null || !gui.active()) {
            return;
        }
        var selection = gui.select(VOLUME_SLIDER);
        if (selection.status() != com.zhongbai233.bench.api.client.gui.BenchGuiSelection.Status.MATCHED) {
            return;
        }
        initialSliderLabel = selection.requireMatch().text();
        if (!"100%".equals(initialSliderLabel)) {
            throw new AssertionError("Turntable volume slider did not start at 100%: " + initialSliderLabel);
        }
        if (!gui.drag(VOLUME_SLIDER, 25.0D, 0.0D)) {
            throw new AssertionError("BenchMod GUI automation did not handle the turntable volume drag");
        }
        adjustedSliderLabel = gui.select(VOLUME_SLIDER).requireMatch().text();
        if ("100%".equals(adjustedSliderLabel)) {
            throw new AssertionError("Turntable volume slider did not move away from 100%");
        }
        advanceTo(2, "volume slider dragged " + initialSliderLabel + " -> " + adjustedSliderLabel);
    }

    private void waitForVolumeSyncAndPrepareDisc(BenchClientContext context) {
        probeVolume(context);
        ModernTurntableBlockEntity client = clientTurntable(context);
        int serverVolume = serverVolumePerMille.get();
        if (client == null || serverVolume < EXPECTED_VOLUME_MIN || serverVolume > EXPECTED_VOLUME_MAX
                || client.getVolumePerMille() != serverVolume) {
            return;
        }
        closeGui(context);
        prepareHand(context, realDisc());
        advanceTo(3, "slider volume synchronized to server and client: " + serverVolume);
    }

    private void insertDiscWhenReady(BenchClientContext context) {
        if (!handReady.get()) {
            return;
        }
        interact(context);
        handReady.set(false);
        advanceTo(4, "real MP3 disc inserted through use-item-on");
    }

    private void waitForInitialAudio(BenchClientContext context, StereoOpenALHandler.DiagnosticSnapshot output) {
        probePlayback(context);
        if (!serverPlaybackObserved.get() || !healthy(context, output, 1)) {
            return;
        }
        firstPcm = output.firstPcm();
        LOGGER.info("真实唱片机滑块范围 Bench: 首次出声，volume={}‰ session={}，开始移动到范围外",
                serverVolumePerMille.get(), serverSession.get());
        advanceTo(5, "initial real PCM healthy; moving outside configured audible range");
    }

    private void waitOutsideAndReturn(BenchClientContext context) {
        if (!serverFarObserved.get() || !outsideConverged()) {
            return;
        }
        LOGGER.info("真实唱片机滑块范围 Bench: 范围外输出已退役，开始返回；demand={}",
                ClientAudioOutputRegistry.audioDemandDebug(turntablePos, null, serverSession.get()));
        advanceTo(7, "outside OpenAL stream retired; returning home");
    }

    private void waitForReturnedAudio(BenchClientContext context,
            StereoOpenALHandler.DiagnosticSnapshot output) {
        if (!serverHomeObserved.get() || !healthy(context, output, 2)) {
            return;
        }
        secondPcm = output.firstPcm();
        LOGGER.info("真实唱片机滑块范围 Bench: 返回后再次出声，准备弹出唱片；demand={}",
                ClientAudioOutputRegistry.audioDemandDebug(turntablePos, null, serverSession.get()));
        prepareHand(context, new ItemStack(Items.STICK));
        advanceTo(9, "returned real PCM healthy; waiting to eject");
    }

    private void ejectWhenReady(BenchClientContext context) {
        if (handReady.get()) {
            interact(context);
            handReady.set(false);
        }
        probeEject(context);
        if (serverEjectObserved.get()) {
            advanceTo(10, "disc ejected; waiting for physical resource convergence");
        }
    }

    private void waitForFinalConvergence(BenchClientContext context) {
        if (serverEjectObserved.get() && clientStopped(context) && resourcesConverged()) {
            converged = true;
        }
    }

    private void moveAndConfirm(BenchClientContext context, BenchClientPose target,
            AtomicBoolean serverObserved, int nextPhase, String observation) {
        moveToward(context, target);
        if (distanceSquared(context.automation().pose(), target) > 0.25D) {
            return;
        }
        probeServerPosition(context, target, serverObserved);
        if (serverObserved.get()) {
            advanceTo(nextPhase, observation + " (client and integrated server observed)");
        }
    }

    private void moveToward(BenchClientContext context, BenchClientPose target) {
        BenchClientPose current = context.automation().pose();
        double dx = target.x() - current.x();
        double dy = target.y() - current.y();
        double dz = target.z() - current.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= MOVE_STEP) {
            context.automation().setPose(target);
        } else {
            double scale = MOVE_STEP / distance;
            context.automation().movePose(new BenchClientPose(
                    current.x() + dx * scale, current.y() + dy * scale, current.z() + dz * scale,
                    target.yaw(), target.pitch()));
        }
        context.automation().stopMovement();
    }

    private void probeVolume(BenchClientContext context) {
        if (serverTaskPending.get()) {
            return;
        }
        submitServer(context, (level, player) -> {
            int volume = turntable(level).getVolumePerMille();
            serverVolumePerMille.set(volume);
            lastObservation.set("server volume=" + volume);
        });
    }

    private void probePlayback(BenchClientContext context) {
        if (serverPlaybackObserved.get() || serverTaskPending.get()) {
            return;
        }
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            String session = turntable.getPlaybackSyncMetadata(level.getGameTime()).sessionId();
            lastObservation.set("server playback: hasDisc=" + turntable.hasDisc() + ", playing="
                    + turntable.isPlaying() + ", rawUrl=" + turntable.getRawUrl() + ", session=" + session);
            if (turntable.hasDisc() && turntable.isPlaying() && properties.url().equals(turntable.getRawUrl())
                    && !session.isBlank()) {
                serverSession.set(session);
                serverPlaybackObserved.set(true);
            }
        });
    }

    private void probeServerPosition(BenchClientContext context, BenchClientPose target,
            AtomicBoolean observed) {
        if (observed.get() || serverTaskPending.get()) {
            return;
        }
        submitServer(context, (level, player) -> {
            double distanceSquared = player.position().distanceToSqr(target.x(), target.y(), target.z());
            lastObservation.set("server player distanceSquared to target=" + distanceSquared
                    + ", target=" + target + ", actual=" + player.position());
            if (distanceSquared < 1.0D) {
                observed.set(true);
            }
        });
    }

    private void probeEject(BenchClientContext context) {
        if (serverEjectObserved.get() || serverTaskPending.get()) {
            return;
        }
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            boolean blockHasDisc = level.getBlockState(turntablePos).getValue(ModernTurntableBlock.HAS_DISC);
            boolean blockPlaying = level.getBlockState(turntablePos).getValue(ModernTurntableBlock.PLAYING);
            lastObservation.set("server eject: hasDisc=" + turntable.hasDisc() + ", playing="
                    + turntable.isPlaying() + ", blockHasDisc=" + blockHasDisc + ", blockPlaying="
                    + blockPlaying);
            if (!turntable.hasDisc() && !turntable.isPlaying() && !blockHasDisc && !blockPlaying) {
                serverEjectObserved.set(true);
            }
        });
    }

    private boolean healthy(BenchClientContext context, StereoOpenALHandler.DiagnosticSnapshot output,
            int expectedStarts) {
        String session = serverSession.get();
        ModernTurntableSound sound = latestSound.get();
        if (output == null || !output.started() || output.firstPcm().samples() < 1_024L
                || streamingStarts.get() != expectedStarts || sound == null
                || !context.minecraft().getSoundManager().isActive(sound)
                || session.isBlank() || !ModernTurntablePlaybackTracker.isActiveSession(turntablePos, session)) {
            return false;
        }
        requirePcmQuality("turntable slider re-entry pass " + expectedStarts, output.firstPcm());
        return true;
    }

    private boolean outsideConverged() {
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        return ClientAudioOutputRegistry.getStereoSnapshot(turntablePos).isEmpty()
                && tap.activeInstances() == tapBaseline.activeInstances()
                && tap.closesCompleted() >= tapBaseline.closesCompleted() + 1L
                && stereo.activeInstances() == stereoBaseline.activeInstances()
                && stereo.cleanupsCompleted() >= stereoBaseline.cleanupsCompleted() + 1L;
    }

    private boolean resourcesConverged() {
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        return ClientAudioOutputRegistry.getStereoSnapshot(turntablePos).isEmpty()
                && tap.activeInstances() == tapBaseline.activeInstances()
                && tap.closesCompleted() >= tapBaseline.closesCompleted() + 2L
                && stereo.activeInstances() == stereoBaseline.activeInstances()
                && stereo.cleanupsCompleted() >= stereoBaseline.cleanupsCompleted() + 2L
                && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                && MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING).currentBytes()
                        == audioStagingBaseline;
    }

    private boolean clientStopped(BenchClientContext context) {
        ModernTurntableBlockEntity turntable = clientTurntable(context);
        return turntable != null && !turntable.hasDisc() && !turntable.isPlaying()
                && !context.level().getBlockState(turntablePos).getValue(ModernTurntableBlock.HAS_DISC)
                && !context.level().getBlockState(turntablePos).getValue(ModernTurntableBlock.PLAYING);
    }

    private void prepareHand(BenchClientContext context, ItemStack stack) {
        pendingHand = stack.copy();
        handReady.set(false);
        context.player().setItemInHand(InteractionHand.MAIN_HAND, pendingHand.copy());
        submitServer(context, (level, player) -> {
            player.setItemInHand(InteractionHand.MAIN_HAND, pendingHand.copy());
            handReady.set(true);
        });
    }

    private void interact(BenchClientContext context) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(turntablePos), Direction.UP,
                turntablePos, false);
        context.minecraft().gameMode.useItemOn(context.player(), InteractionHand.MAIN_HAND, hit);
    }

    private ItemStack realDisc() {
        ItemStack stack = new ItemStack(InitItems.MUSIC_CD.get());
        return ItemMusicCD.setSongInfo(new ItemMusicCD.SongInfo(
                properties.url(), "real turntable slider range re-entry", 360, false), stack);
    }

    private void closeGui(BenchClientContext context) {
        if (gui != null) {
            gui.close();
            gui = null;
        }
        if (context.minecraft().screen instanceof ModernTurntableScreen) {
            context.minecraft().setScreen(null);
        }
    }

    private void submitServer(BenchClientContext context, ServerAction action) {
        if (!serverTaskPending.compareAndSet(false, true)) {
            return;
        }
        var server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            serverTaskPending.set(false);
            failure.compareAndSet(null, new IllegalStateException("Integrated server is unavailable"));
            return;
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || !(player.level() instanceof ServerLevel level)) {
                    throw new IllegalStateException("Integrated server player is unavailable");
                }
                action.run(level, player);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                serverTaskPending.set(false);
            }
        });
    }

    private ModernTurntableBlockEntity turntable(ServerLevel level) {
        if (level.getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity turntable) {
            return turntable;
        }
        throw new AssertionError("Modern turntable block entity is missing at " + turntablePos);
    }

    private ModernTurntableBlockEntity clientTurntable(BenchClientContext context) {
        return context.level().getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity turntable
                ? turntable : null;
    }

    private void advanceTo(int nextPhase, String observation) {
        phase = nextPhase;
        phaseTicks = 0;
        lastObservation.set(observation);
    }

    private void throwIfFailed() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("Real turntable volume range re-entry failed", error);
        }
    }

    private static double distanceSquared(BenchClientPose left, BenchClientPose right) {
        double dx = left.x() - right.x();
        double dy = left.y() - right.y();
        double dz = left.z() - right.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static StereoOpenALHandler.PcmQuality emptyPcm() {
        return new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D);
    }

    @FunctionalInterface
    private interface ServerAction {
        void run(ServerLevel level, ServerPlayer player) throws Exception;
    }
}
