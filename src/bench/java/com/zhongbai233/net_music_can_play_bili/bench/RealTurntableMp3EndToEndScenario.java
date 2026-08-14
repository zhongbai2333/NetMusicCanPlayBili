package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requirePcmQuality;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.github.tartaricacid.netmusic.init.InitItems;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.block.ModernTurntableBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntableSound;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class RealTurntableMp3EndToEndScenario implements BenchClientScenario {
    private static final int MAX_PHASE_TICKS = 600;
    private static final BenchMetricDescriptor CHANNEL_STARTS = new BenchMetricDescriptor(
            "ncpb.real_turntable_mp3.channel_starts", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor AUDIBLE_MILLIS = new BenchMetricDescriptor(
            "ncpb.real_turntable_mp3.audible_millis", "milliseconds", MetricDirection.NEUTRAL);

    private final AudioStreamProperties.RealMp3Bench properties = AudioStreamProperties.realMp3Bench();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean serverTaskPending = new AtomicBoolean();
    private final AtomicBoolean setupComplete = new AtomicBoolean();
    private final AtomicBoolean handReady = new AtomicBoolean();
    private final AtomicBoolean serverPlaybackObserved = new AtomicBoolean();
    private final AtomicBoolean serverEjectObserved = new AtomicBoolean();
    private final AtomicReference<String> serverSession = new AtomicReference<>("");
    private final AtomicReference<ModernTurntableSound> sound = new AtomicReference<>();
    private final AtomicInteger streamingChannelStarts = new AtomicInteger();
    private final AtomicReference<String> lastObservation = new AtomicReference<>("not started");
    private final Consumer<PlayStreamingSourceEvent> streamingListener = event -> {
        if (event.getSound() instanceof ModernTurntableSound modernSound) {
            sound.compareAndSet(null, modernSound);
            streamingChannelStarts.incrementAndGet();
        }
    };

    private OpenALTappedAudioInputStream.LifecycleSnapshot tapBaseline;
    private StereoOpenALHandler.LifecycleSnapshot stereoBaseline;
    private StereoOpenALHandler.PcmQuality pcm = new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D);
    private long audioStagingBaseline;
    private BlockPos turntablePos;
    private ItemStack pendingHand = ItemStack.EMPTY;
    private UUID playerId;
    private boolean listenerRegistered;
    private boolean converged;
    private int phase;
    private int phaseTicks;

    @Override
    public void setup(BenchClientContext context) {
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
        ModernTurntablePlaybackTracker.stopAllSounds();
        playerId = context.player().getUUID();
        turntablePos = context.player().blockPosition().offset(2, 0, 2).immutable();
        tapBaseline = OpenALTappedAudioInputStream.lifecycleSnapshot();
        stereoBaseline = StereoOpenALHandler.lifecycleSnapshot();
        audioStagingBaseline = MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING)
                .currentBytes();
        NeoForge.EVENT_BUS.addListener(PlayStreamingSourceEvent.class, streamingListener);
        listenerRegistered = true;
        submitServer(context, (level, player) -> {
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
        ClientAudioOutputRegistry.updatePositions(new float[] {
                (float) context.player().getX(), (float) context.player().getEyeY(),
                (float) context.player().getZ()
        });
        OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
        StereoOpenALHandler.DiagnosticSnapshot output = ClientAudioOutputRegistry
                .getStereoSnapshot(turntablePos).orElse(null);
        if (output != null && output.firstPcm().samples() > 0L) {
            pcm = output.firstPcm();
        }
        context.metrics().record(CHANNEL_STARTS, streamingChannelStarts.get());
        context.metrics().record(AUDIBLE_MILLIS, output != null ? output.positionMillis() : -1L);

        switch (phase) {
            case 0 -> {
                if (!serverTaskPending.get()) {
                    prepareHand(context, realDisc());
                    advanceTo(1, "waiting for real MP3 disc hand sync");
                }
            }
            case 1 -> {
                if (handReady.get()) {
                    interact(context);
                    advanceTo(2, "real MP3 use-item-on packet sent");
                }
            }
            case 2 -> {
                probePlayback(context);
                ModernTurntableSound activeSound = sound.get();
                String expectedSession = serverSession.get();
                if (serverPlaybackObserved.get() && activeSound != null && output != null && output.started()
                        && output.firstPcm().samples() >= 1_024L
                        && streamingChannelStarts.get() == 1
                        && context.minecraft().getSoundManager().isActive(activeSound)
                        && !expectedSession.isBlank()
                        && activeSound.playbackSession().filter(id -> expectedSession.equals(id.value())).isPresent()
                        && ModernTurntablePlaybackTracker.isActiveSession(turntablePos, expectedSession)) {
                    requirePcmQuality("real turntable end-to-end", output.firstPcm());
                    pcm = output.firstPcm();
                    prepareHand(context, new ItemStack(Items.STICK));
                    advanceTo(3, "waiting for eject hand sync");
                }
            }
            case 3 -> {
                if (handReady.get()) {
                    interact(context);
                    advanceTo(4, "real MP3 eject packet sent");
                }
            }
            case 4 -> {
                probeEject(context);
                if (serverEjectObserved.get() && clientStopped(context) && resourcesConverged(context)) {
                    converged = true;
                    return BenchClientStepResult.COMPLETE;
                }
            }
            default -> throw new AssertionError("Unexpected real turntable MP3 phase " + phase);
        }
        if (phaseTicks > MAX_PHASE_TICKS) {
            throw new AssertionError("Real turntable MP3 end-to-end stalled in phase " + phase + ": "
                    + lastObservation.get() + ", channels=" + streamingChannelStarts + ", output=" + output
                    + ", tap=" + OpenALTappedAudioInputStream.lifecycleSnapshot() + ", stereo="
                    + StereoOpenALHandler.lifecycleSnapshot());
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        requirePcmQuality("real turntable end-to-end", pcm);
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        if (!converged || !serverPlaybackObserved.get() || !serverEjectObserved.get()
                || streamingChannelStarts.get() != 1 || !resourcesConverged(context)
                || tap.instancesCreated() != tapBaseline.instancesCreated() + 1L
                || tap.closesCompleted() != tapBaseline.closesCompleted() + 1L
                || stereo.instancesCreated() != stereoBaseline.instancesCreated() + 1L
                || stereo.cleanupsStarted() != stereoBaseline.cleanupsStarted() + 1L
                || stereo.cleanupsCompleted() != stereoBaseline.cleanupsCompleted() + 1L) {
            throw new AssertionError("Real turntable MP3 end-to-end did not converge exactly: channels="
                    + streamingChannelStarts + ", session=" + serverSession + ", tapBaseline=" + tapBaseline
                    + ", tap=" + tap + ", stereoBaseline=" + stereoBaseline + ", stereo=" + stereo);
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        context.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        ModernTurntablePlaybackTracker.stopAllSounds();
        if (listenerRegistered) {
            NeoForge.EVENT_BUS.unregister(streamingListener);
            listenerRegistered = false;
        }
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
        var server = context.minecraft().getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && player.level() instanceof ServerLevel level) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    level.getEntitiesOfClass(ItemEntity.class, new AABB(turntablePos).inflate(3.0D))
                            .forEach(ItemEntity::discard);
                    level.setBlockAndUpdate(turntablePos, Blocks.AIR.defaultBlockState());
                }
            });
        }
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

    private boolean clientStopped(BenchClientContext context) {
        ModernTurntableBlockEntity turntable = clientTurntable(context);
        return turntable != null && !turntable.hasDisc() && !turntable.isPlaying()
                && !context.level().getBlockState(turntablePos).getValue(ModernTurntableBlock.HAS_DISC)
                && !context.level().getBlockState(turntablePos).getValue(ModernTurntableBlock.PLAYING);
    }

    private boolean resourcesConverged(BenchClientContext context) {
        ModernTurntableSound activeSound = sound.get();
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        return activeSound != null && !context.minecraft().getSoundManager().isActive(activeSound)
                && !ModernTurntablePlaybackTracker.isActiveSession(turntablePos, serverSession.get())
                && ClientAudioOutputRegistry.getStereoSnapshot(turntablePos).isEmpty()
                && tap.activeInstances() == tapBaseline.activeInstances()
                && tap.closesCompleted() >= tapBaseline.closesCompleted() + 1L
                && stereo.activeInstances() == stereoBaseline.activeInstances()
                && stereo.cleanupsCompleted() >= stereoBaseline.cleanupsCompleted() + 1L
                && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                && MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING).currentBytes()
                        == audioStagingBaseline;
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
        handReady.set(false);
        context.player().setItemInHand(InteractionHand.MAIN_HAND, pendingHand.copy());
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(turntablePos), Direction.UP,
                turntablePos, false);
        context.minecraft().gameMode.useItemOn(context.player(), InteractionHand.MAIN_HAND, hit);
    }

    private ItemStack realDisc() {
        ItemStack stack = new ItemStack(InitItems.MUSIC_CD.get());
        return ItemMusicCD.setSongInfo(new ItemMusicCD.SongInfo(
                properties.url(), "real turntable MP3 end-to-end", 360, false), stack);
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
            throw new AssertionError("Real turntable MP3 end-to-end failed", error);
        }
    }

    @FunctionalInterface
    private interface ServerAction {
        void run(ServerLevel level, ServerPlayer player) throws Exception;
    }
}
