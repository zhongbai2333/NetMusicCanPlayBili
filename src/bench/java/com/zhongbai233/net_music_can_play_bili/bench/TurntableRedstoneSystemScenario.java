package com.zhongbai233.net_music_can_play_bili.bench;

import com.github.tartaricacid.netmusic.init.InitItems;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.TurntableRedstoneMode;
import com.zhongbai233.net_music_can_play_bili.blockentity.IndexedBlockPlaybackSessionManager;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackCoordinator;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Real integrated-server coverage for every modern-turntable redstone contract. */
final class TurntableRedstoneSystemScenario implements BenchClientScenario {
    private static final int MAX_PHASE_TICKS = 160;
    private static final BenchMetricDescriptor CHECKS = new BenchMetricDescriptor(
            "ncpb.turntable_redstone.checks", "checks", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor SIGNAL_TRANSITIONS = new BenchMetricDescriptor(
            "ncpb.turntable_redstone.signal_transitions", "transitions", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor COMPARATOR_OUTPUT = new BenchMetricDescriptor(
            "ncpb.turntable_redstone.comparator_output", "signal", MetricDirection.NEUTRAL);

    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<String> lastObservation = new AtomicReference<>("not started");
    private final AtomicBoolean serverTaskPending = new AtomicBoolean();
    private final AtomicBoolean setupComplete = new AtomicBoolean();
    private final AtomicInteger serverMilestone = new AtomicInteger(-1);
    private final AtomicInteger signalTransitions = new AtomicInteger();
    private final AtomicInteger comparatorOutput = new AtomicInteger();
    private final AtomicLong pausedElapsedMillis = new AtomicLong();
    private final Set<Check> checks = ConcurrentHashMap.newKeySet();

    private BlockPos turntablePos;
    private BlockPos powerPos;
    private BlockPos comparatorPos;
    private UUID playerId;
    private int phase;
    private int phaseTicks;

    @Override
    public void setup(BenchClientContext context) {
        ModernTurntablePlaybackCoordinator.clearPendingPrepares();
        ModernTurntablePlaybackTracker.stopAllSounds();
        ClientAudioOutputRegistry.cleanup();
        playerId = context.player().getUUID();
        turntablePos = context.player().blockPosition().offset(6, 0, 6).immutable();
        powerPos = turntablePos.north().immutable();
        comparatorPos = turntablePos.east().immutable();
        submitServer(context, (level, player) -> {
            clearFixture(level);
            level.setBlockAndUpdate(turntablePos, ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());
            turntable(level).setVolumePerMille(0);
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
        context.metrics().record(CHECKS, checks.size());
        context.metrics().record(SIGNAL_TRANSITIONS, signalTransitions.get());
        context.metrics().record(COMPARATOR_OUTPUT, comparatorOutput.get());

        switch (phase) {
            case 0 -> configureHighModeAndInsertDisc(context);
            case 1 -> verifyHighWithoutPowerBlocksPlayback(context);
            case 2 -> setPower(context, true, 2);
            case 3 -> verifyHighPowerStartsAndOwnsPlayback(context);
            case 4 -> setPower(context, false, 4);
            case 5 -> verifyHighPowerLossPausesAndBlocksManualStart(context);
            case 6 -> verifyPauseStabilityAndRestorePower(context);
            case 7 -> verifyHighResumeAndLowPoweredPause(context);
            case 8 -> setPower(context, false, 8);
            case 9 -> verifyLowUnpoweredResumeAndEnterPulse(context);
            case 10 -> verifyFirstPulsePauses(context);
            case 11 -> verifyPulseFallingEdgeDoesNothing(context);
            case 12 -> verifySecondPulseResumesAndEnterIgnore(context);
            case 13 -> verifyIgnoreAndCreateComparator(context);
            case 14 -> verifyPhysicalComparator(context);
            case 15 -> {
                ModernTurntableBlockEntity client = clientTurntable(context);
                if (client != null && client.getRedstoneMode() == TurntableRedstoneMode.IGNORE
                        && client.isPlaying()) {
                    checks.add(Check.CLIENT_STATE_SYNCED);
                    context.metrics().record(CHECKS, checks.size());
                    return BenchClientStepResult.COMPLETE;
                }
                lastObservation.set("waiting for final client state sync: client=" + describe(client));
            }
            default -> throw new AssertionError("Unexpected turntable redstone phase " + phase);
        }

        if (phaseTicks > MAX_PHASE_TICKS) {
            throw new AssertionError("Modern turntable redstone scenario stalled in phase " + phase
                    + " after " + phaseTicks + " ticks; " + lastObservation.get());
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        EnumSet<Check> missing = EnumSet.allOf(Check.class);
        missing.removeAll(checks);
        if (!missing.isEmpty() || signalTransitions.get() != 8 || comparatorOutput.get() != 8) {
            throw new AssertionError("Modern turntable redstone coverage incomplete: missing=" + missing
                    + ", transitions=" + signalTransitions + ", comparator=" + comparatorOutput
                    + ", last=" + lastObservation.get());
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        ModernTurntablePlaybackCoordinator.clearPendingPrepares();
        ModernTurntablePlaybackTracker.stopAllSounds();
        ClientAudioOutputRegistry.cleanup();
        var server = context.minecraft().getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && player.level() instanceof ServerLevel level) {
                    clearFixture(level);
                }
            });
        }
    }

    private void configureHighModeAndInsertDisc(BenchClientContext context) {
        if (advanceWhenComplete(0, "high mode configured with an automated disc and no power")) {
            return;
        }
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            setMode(level, turntable, TurntableRedstoneMode.HIGH_SIGNAL);
            require(!level.hasNeighborSignal(turntablePos), "fixture unexpectedly powers the turntable");
            ItemStack disc = disc("bench turntable redstone matrix");
            try (Transaction transaction = Transaction.openRoot()) {
                int inserted = turntable.getItemHandler().insert(0, ItemResource.of(disc), 1, transaction);
                require(inserted == 1, "automation inserted " + inserted + " disc(s)");
                transaction.commit();
            }
            require(turntable.hasDisc(), "automation insertion did not commit");
            markMilestone(0, "high mode disc inserted without power: " + describe(level, turntable));
        });
    }

    private void verifyHighWithoutPowerBlocksPlayback(BenchClientContext context) {
        if (advanceWhenComplete(1, "high mode blocked playback without power")) {
            return;
        }
        if (phaseTicks < 8) {
            return;
        }
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            require(turntable.getRedstoneMode() == TurntableRedstoneMode.HIGH_SIGNAL,
                    "turntable left high-signal mode");
            require(!level.hasNeighborSignal(turntablePos), "turntable is powered during no-power check");
            require(turntable.hasDisc() && !turntable.isPlaying(),
                    "high mode played without power: " + describe(level, turntable));
            checks.add(Check.HIGH_UNPOWERED_BLOCKS);
            markMilestone(1, "high mode correctly remained paused without power");
        });
    }

    private void setPower(BenchClientContext context, boolean powered, int milestone) {
        if (advanceWhenComplete(milestone, (powered ? "applied" : "removed") + " direct redstone power")) {
            return;
        }
        submitServer(context, (level, player) -> {
            level.setBlockAndUpdate(powerPos,
                    powered ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
            require(level.hasNeighborSignal(turntablePos) == powered,
                    "direct redstone sample did not become " + powered);
            signalTransitions.incrementAndGet();
            markMilestone(milestone, "power=" + powered + ": " + describe(level, turntable(level)));
        });
    }

    private void verifyHighPowerStartsAndOwnsPlayback(BenchClientContext context) {
        if (advanceWhenComplete(3, "high power started playback and rejected manual pause")) {
            return;
        }
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            long elapsed = turntable.getPlaybackElapsedMillis();
            if (!turntable.isPlaying() || elapsed < 250L) {
                lastObservation.set("waiting for powered high-mode playback: " + describe(level, turntable));
                return;
            }
            require(level.hasNeighborSignal(turntablePos), "high-mode playback started without sampled power");
            checks.add(Check.HIGH_POWERED_AUTOMATION_STARTS);
            turntable.pauseFromControl(level);
            require(turntable.isPlaying(), "manual pause overrode powered high-signal mode");
            checks.add(Check.HIGH_POWERED_OWNS_PLAYBACK);
            markMilestone(3, "powered high mode playing at " + elapsed + "ms");
        });
    }

    private void verifyHighPowerLossPausesAndBlocksManualStart(BenchClientContext context) {
        if (advanceWhenComplete(5, "high power loss paused and rejected manual start")) {
            return;
        }
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            if (turntable.isPlaying()) {
                lastObservation.set("waiting for high-mode pause after power loss: " + describe(level, turntable));
                return;
            }
            require(!level.hasNeighborSignal(turntablePos), "power returned during high-mode pause check");
            long elapsed = turntable.getPlaybackElapsedMillis();
            require(elapsed > 0L, "high-mode pause lost the playback position");
            pausedElapsedMillis.set(elapsed);
            checks.add(Check.HIGH_UNPOWERED_PAUSES);
            turntable.resumePlayback(player, -1L);
            require(!turntable.isPlaying(), "manual start bypassed unpowered high-signal mode");
            checks.add(Check.HIGH_UNPOWERED_BLOCKS_MANUAL_START);
            require(!IndexedBlockPlaybackSessionManager.contains(turntable.getPlaybackSourceId()),
                    "paused high-signal turntable remained in the server playback index");
            checks.add(Check.SERVER_INDEX_RETIRED_ON_PAUSE);
            markMilestone(5, "high mode paused at " + elapsed + "ms and retired the indexed session");
        });
    }

    private void verifyPauseStabilityAndRestorePower(BenchClientContext context) {
        if (advanceWhenComplete(6, "paused timeline stayed stable before high power returned")) {
            return;
        }
        if (phaseTicks < 30) {
            return;
        }
        require(ModernTurntablePlaybackCoordinator.indexedDemandDebugSnapshots().isEmpty(),
                "paused turntable recreated client audio demand after the server resync interval: "
                        + ModernTurntablePlaybackCoordinator.indexedDemandDebugSnapshots());
        checks.add(Check.CLIENT_AUDIO_RETRY_QUIESCENT);
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            long elapsed = turntable.getPlaybackElapsedMillis();
            require(!turntable.isPlaying(), "high-mode pause resumed without power");
            require(Math.abs(elapsed - pausedElapsedMillis.get()) <= 50L,
                    "paused timeline drifted from " + pausedElapsedMillis.get() + "ms to " + elapsed + "ms");
            checks.add(Check.HIGH_PAUSE_STABLE);
            level.setBlockAndUpdate(powerPos, Blocks.REDSTONE_BLOCK.defaultBlockState());
            require(level.hasNeighborSignal(turntablePos), "failed to restore high-mode power");
            signalTransitions.incrementAndGet();
            markMilestone(6, "restored high power after stable pause at " + elapsed + "ms");
        });
    }

    private void verifyHighResumeAndLowPoweredPause(BenchClientContext context) {
        if (advanceWhenComplete(7, "high resumed from progress; low mode paused while powered")) {
            return;
        }
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            long elapsed = turntable.getPlaybackElapsedMillis();
            if (!turntable.isPlaying()) {
                lastObservation.set("waiting for high-mode resume: " + describe(level, turntable));
                return;
            }
            require(elapsed >= pausedElapsedMillis.get(),
                    "high-mode resume reset progress from " + pausedElapsedMillis.get() + "ms to " + elapsed + "ms");
            checks.add(Check.HIGH_RESUMES_WITH_PROGRESS);
            setMode(level, turntable, TurntableRedstoneMode.LOW_SIGNAL);
            require(!turntable.isPlaying(), "powered low-signal mode did not pause immediately");
            checks.add(Check.LOW_POWERED_PAUSES);
            markMilestone(7, "high resumed at " + elapsed + "ms; powered low mode paused");
        });
    }

    private void verifyLowUnpoweredResumeAndEnterPulse(BenchClientContext context) {
        if (advanceWhenComplete(9, "low mode resumed without power and entered pulse mode")) {
            return;
        }
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            if (!turntable.isPlaying()) {
                lastObservation.set("waiting for unpowered low-mode resume: " + describe(level, turntable));
                return;
            }
            require(!level.hasNeighborSignal(turntablePos), "low mode resumed while fixture remained powered");
            checks.add(Check.LOW_UNPOWERED_RESUMES);
            setMode(level, turntable, TurntableRedstoneMode.PULSE_TOGGLE);
            require(turntable.isPlaying(), "entering pulse mode stopped existing playback");
            level.setBlockAndUpdate(powerPos, Blocks.REDSTONE_BLOCK.defaultBlockState());
            require(level.hasNeighborSignal(turntablePos), "failed to create first pulse rising edge");
            signalTransitions.incrementAndGet();
            markMilestone(9, "entered pulse mode while playing and raised first pulse");
        });
    }

    private void verifyFirstPulsePauses(BenchClientContext context) {
        if (advanceWhenComplete(10, "first pulse rising edge paused existing playback")) {
            return;
        }
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            if (turntable.isPlaying()) {
                lastObservation.set("waiting for first pulse to pause: " + describe(level, turntable));
                return;
            }
            require(level.hasNeighborSignal(turntablePos), "first pulse disappeared before pause");
            checks.add(Check.PULSE_FIRST_RISE_PAUSES);
            level.setBlockAndUpdate(powerPos, Blocks.AIR.defaultBlockState());
            signalTransitions.incrementAndGet();
            markMilestone(10, "first pulse paused playback; falling edge submitted");
        });
    }

    private void verifyPulseFallingEdgeDoesNothing(BenchClientContext context) {
        if (advanceWhenComplete(11, "pulse falling edge preserved paused state")) {
            return;
        }
        if (phaseTicks < 4) {
            return;
        }
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            require(!level.hasNeighborSignal(turntablePos) && !turntable.isPlaying(),
                    "pulse falling edge changed playback: " + describe(level, turntable));
            checks.add(Check.PULSE_FALLING_EDGE_STABLE);
            level.setBlockAndUpdate(powerPos, Blocks.REDSTONE_BLOCK.defaultBlockState());
            signalTransitions.incrementAndGet();
            markMilestone(11, "falling edge stayed paused; second rising edge submitted");
        });
    }

    private void verifySecondPulseResumesAndEnterIgnore(BenchClientContext context) {
        if (advanceWhenComplete(12, "second pulse resumed and ignore mode was selected")) {
            return;
        }
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            if (!turntable.isPlaying()) {
                lastObservation.set("waiting for second pulse to resume: " + describe(level, turntable));
                return;
            }
            checks.add(Check.PULSE_SECOND_RISE_RESUMES);
            setMode(level, turntable, TurntableRedstoneMode.IGNORE);
            require(turntable.isPlaying(), "entering ignore mode stopped playback");
            level.setBlockAndUpdate(powerPos, Blocks.AIR.defaultBlockState());
            signalTransitions.incrementAndGet();
            markMilestone(12, "second pulse resumed; ignore mode selected and power removed");
        });
    }

    private void verifyIgnoreAndCreateComparator(BenchClientContext context) {
        if (advanceWhenComplete(13, "ignore mode survived power loss and comparator was created")) {
            return;
        }
        if (phaseTicks < 4) {
            return;
        }
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            require(turntable.getRedstoneMode() == TurntableRedstoneMode.IGNORE
                            && !level.hasNeighborSignal(turntablePos) && turntable.isPlaying(),
                    "ignore mode followed removed power: " + describe(level, turntable));
            checks.add(Check.IGNORE_UNPOWERED_CONTINUES);
            level.setBlockAndUpdate(comparatorPos.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(comparatorPos,
                    Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST));
            turntable.seekTo(level, 60_000L);
            require(turntable.getComparatorOutput() == 8,
                    "turntable half-progress signal was " + turntable.getComparatorOutput());
            markMilestone(13, "ignore mode continued; comparator scheduled at half progress");
        });
    }

    private void verifyPhysicalComparator(BenchClientContext context) {
        if (advanceWhenComplete(14, "physical comparator observed signal 8")) {
            return;
        }
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            int direct = turntable.getComparatorOutput();
            int physical = level.getBlockEntity(comparatorPos) instanceof ComparatorBlockEntity comparator
                    ? comparator.getOutputSignal() : -1;
            lastObservation.set("comparator direct=" + direct + " physical=" + physical);
            if (physical != 8) {
                return;
            }
            require(direct == 8, "physical comparator converged while source output was " + direct);
            comparatorOutput.set(physical);
            checks.add(Check.COMPARATOR_HALF_PROGRESS_OUTPUT);
            markMilestone(14, "physical comparator converged to signal 8");
        });
    }

    private boolean advanceWhenComplete(int milestone, String observation) {
        if (serverMilestone.get() < milestone) {
            return false;
        }
        advanceTo(milestone + 1, observation);
        return true;
    }

    private void markMilestone(int milestone, String observation) {
        serverMilestone.set(milestone);
        lastObservation.set(observation);
    }

    private void advanceTo(int nextPhase, String observation) {
        phase = nextPhase;
        phaseTicks = 0;
        lastObservation.set(observation);
    }

    private void setMode(ServerLevel level, ModernTurntableBlockEntity turntable, TurntableRedstoneMode target) {
        for (int i = 0; i < TurntableRedstoneMode.values().length && turntable.getRedstoneMode() != target; i++) {
            turntable.cycleRedstoneMode(level);
        }
        require(turntable.getRedstoneMode() == target,
                "failed to select redstone mode " + target + ", got " + turntable.getRedstoneMode());
    }

    private void clearFixture(ServerLevel level) {
        level.setBlockAndUpdate(powerPos, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(comparatorPos, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(comparatorPos.below(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(turntablePos, Blocks.AIR.defaultBlockState());
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

    private static ItemStack disc(String name) {
        ItemStack stack = new ItemStack(InitItems.MUSIC_CD.get());
        return ItemMusicCD.setSongInfo(new ItemMusicCD.SongInfo(
                "https://example.test/bench-redstone.mp3", name, 120, false), stack);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private String describe(ServerLevel level, ModernTurntableBlockEntity turntable) {
        return "mode=" + turntable.getRedstoneMode() + ", powered=" + level.hasNeighborSignal(turntablePos)
                + ", hasDisc=" + turntable.hasDisc() + ", playing=" + turntable.isPlaying()
                + ", elapsed=" + turntable.getPlaybackElapsedMillis() + "ms";
    }

    private static String describe(ModernTurntableBlockEntity turntable) {
        return turntable == null ? "missing"
                : "mode=" + turntable.getRedstoneMode() + ", hasDisc=" + turntable.hasDisc()
                        + ", playing=" + turntable.isPlaying() + ", elapsed="
                        + turntable.getPlaybackElapsedMillis() + "ms";
    }

    private void throwIfFailed() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("Modern turntable redstone system failed; " + lastObservation.get(), error);
        }
    }

    private enum Check {
        HIGH_UNPOWERED_BLOCKS,
        HIGH_POWERED_AUTOMATION_STARTS,
        HIGH_POWERED_OWNS_PLAYBACK,
        HIGH_UNPOWERED_PAUSES,
        HIGH_UNPOWERED_BLOCKS_MANUAL_START,
        HIGH_PAUSE_STABLE,
        HIGH_RESUMES_WITH_PROGRESS,
        LOW_POWERED_PAUSES,
        LOW_UNPOWERED_RESUMES,
        PULSE_FIRST_RISE_PAUSES,
        PULSE_FALLING_EDGE_STABLE,
        PULSE_SECOND_RISE_RESUMES,
        IGNORE_UNPOWERED_CONTINUES,
        COMPARATOR_HALF_PROGRESS_OUTPUT,
        SERVER_INDEX_RETIRED_ON_PAUSE,
        CLIENT_AUDIO_RETRY_QUIESCENT,
        CLIENT_STATE_SYNCED
    }

    @FunctionalInterface
    private interface ServerAction {
        void run(ServerLevel level, ServerPlayer player) throws Exception;
    }
}
