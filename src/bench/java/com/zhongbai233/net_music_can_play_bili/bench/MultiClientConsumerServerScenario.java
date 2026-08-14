package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.MULTI_CLIENT_CONSOLE_POS;
import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.MULTI_CLIENT_SOURCE_POS;
import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.MULTI_CLIENT_PLAYER_COUNT;
import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.MULTI_CLIENT_LEASE_COUNT;

import com.zhongbai233.bench.api.neoforge.server.BenchServerContext;
import com.zhongbai233.bench.api.neoforge.server.BenchServerScenario;
import com.zhongbai233.bench.api.neoforge.server.BenchStepResult;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.server.ControlConsoleConsumerLeaseRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;

final class MultiClientConsumerServerScenario implements BenchServerScenario {
    private final Set<UUID> initialPlayers = new java.util.HashSet<>();
    private UUID survivingPlayer;
    private boolean observedTwoLeases;
    private boolean observedIndependentSurvivor;
    private boolean observedFinalCleanup;
    private int warmupTicks;
    private int measureTicks;
    private int phase;

    @Override
    public void setup(BenchServerContext context) {
        if (context.server().getPlayerList().getPlayerCount() != 2) {
            throw new AssertionError("Expected exactly two paired clients, got "
                    + context.server().getPlayerList().getPlayerCount());
        }
        ServerLevel level = context.level();
        ControlConsoleConsumerLeaseRegistry.clear();
        for (int x = -2; x <= 3; x++) {
            for (int z = -2; z <= 3; z++) {
                level.setBlockAndUpdate(new BlockPos(x, MULTI_CLIENT_CONSOLE_POS.getY() - 1, z),
                        Blocks.STONE.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(MULTI_CLIENT_SOURCE_POS, ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());
        level.setBlockAndUpdate(MULTI_CLIENT_CONSOLE_POS, ModBlocks.CONTROL_CONSOLE.get().defaultBlockState());
        if (!(level.getBlockEntity(MULTI_CLIENT_CONSOLE_POS) instanceof ControlConsoleBlockEntity console)) {
            throw new AssertionError("Paired console block entity was not created");
        }
        console.linkTo(level.dimension().identifier().toString(), MULTI_CLIENT_SOURCE_POS);
        int index = 0;
        for (ServerPlayer player : context.server().getPlayerList().getPlayers()) {
            initialPlayers.add(player.getUUID());
            double x = MULTI_CLIENT_CONSOLE_POS.getX() + 0.25D + index * 0.5D;
            if (!player.teleportTo(level, x, MULTI_CLIENT_CONSOLE_POS.getY() + 1.0D,
                    MULTI_CLIENT_CONSOLE_POS.getZ() + 2.5D, Set.<Relative>of(), 180.0F, 0.0F, true)) {
                throw new AssertionError("Could not place paired client " + player.getUUID());
            }
            // The paired suite runs in the void preset.  Rendering-heavy shaderpack
            // frames can stretch several client ticks, so do not let gravity turn a
            // media/lease test into an unrelated fall-damage test.
            player.setNoGravity(true);
            index++;
        }
        if (initialPlayers.size() != 2) {
            throw new AssertionError("Paired clients did not have distinct UUIDs: " + initialPlayers);
        }
    }

    @Override
    public BenchStepResult stabilize(BenchServerContext context) {
        keepPairedPlayersSafe(context);
        Set<UUID> active = activePlayers(context);
        record(context, active.size());
        if (active.size() == 2 && active.equals(initialPlayers)
                && context.server().getPlayerList().getPlayerCount() == 2) {
            observedTwoLeases = true;
            return BenchStepResult.COMPLETE;
        }
        return BenchStepResult.CONTINUE;
    }

    @Override
    public BenchStepResult warmup(BenchServerContext context) {
        keepPairedPlayersSafe(context);
        Set<UUID> active = activePlayers(context);
        record(context, active.size());
        if (active.size() != 2 || !active.equals(initialPlayers)
                || context.server().getPlayerList().getPlayerCount() != 2) {
            throw new AssertionError("A paired lease disappeared while both clients were connected: players="
                    + context.server().getPlayerList().getPlayerCount() + ", leases=" + active);
        }
        return ++warmupTicks >= 20 ? BenchStepResult.COMPLETE : BenchStepResult.CONTINUE;
    }

    @Override
    public BenchStepResult measure(BenchServerContext context) {
        keepPairedPlayersSafe(context);
        measureTicks++;
        Set<UUID> active = activePlayers(context);
        int players = context.server().getPlayerList().getPlayerCount();
        record(context, active.size());
        if (phase == 0 && players == 1 && active.size() == 1) {
            survivingPlayer = active.iterator().next();
            if (!initialPlayers.contains(survivingPlayer)) {
                throw new AssertionError("Unknown lease survived the first disconnect: " + survivingPlayer);
            }
            observedIndependentSurvivor = true;
            phase = 1;
        } else if (phase == 1 && players == 0 && active.isEmpty()) {
            observedFinalCleanup = true;
            return BenchStepResult.COMPLETE;
        }
        if (measureTicks > 600) {
            throw new AssertionError("Paired disconnect lifecycle stalled: phase=" + phase
                    + ", players=" + players + ", leases=" + active + ", survivor=" + survivingPlayer);
        }
        return BenchStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchServerContext context) {
        Set<UUID> active = activePlayers(context);
        if (!observedTwoLeases || !observedIndependentSurvivor || !observedFinalCleanup
                || context.server().getPlayerList().getPlayerCount() != 0 || !active.isEmpty()) {
            throw new AssertionError("Paired consumer lifecycle did not prove 2 -> 1 -> 0: initial="
                    + observedTwoLeases + ", survivor=" + observedIndependentSurvivor + ", final="
                    + observedFinalCleanup + ", players="
                    + context.server().getPlayerList().getPlayerCount() + ", leases=" + active);
        }
    }

    @Override
    public void teardown(BenchServerContext context) {
        ControlConsoleConsumerLeaseRegistry.clear();
        ServerLevel level = context.level();
        level.setBlockAndUpdate(MULTI_CLIENT_CONSOLE_POS, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(MULTI_CLIENT_SOURCE_POS, Blocks.AIR.defaultBlockState());
        for (int x = -2; x <= 3; x++) {
            for (int z = -2; z <= 3; z++) {
                level.setBlockAndUpdate(new BlockPos(x, MULTI_CLIENT_CONSOLE_POS.getY() - 1, z),
                        Blocks.AIR.defaultBlockState());
            }
        }
    }

    private Set<UUID> activePlayers(BenchServerContext context) {
        return ControlConsoleConsumerLeaseRegistry.activePlayers(
                context.level().dimension().identifier().toString(), MULTI_CLIENT_CONSOLE_POS.asLong(),
                System.currentTimeMillis());
    }

    private void keepPairedPlayersSafe(BenchServerContext context) {
        ServerLevel level = context.level();
        int index = 0;
        for (ServerPlayer player : context.server().getPlayerList().getPlayers()) {
            if (!initialPlayers.contains(player.getUUID())) {
                continue;
            }
            player.setNoGravity(true);
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
            if (player.getY() < MULTI_CLIENT_CONSOLE_POS.getY()
                    || player.position().distanceTo(Vec3.atCenterOf(MULTI_CLIENT_CONSOLE_POS)) > 8.0D) {
                double x = MULTI_CLIENT_CONSOLE_POS.getX() + 0.25D + index * 0.5D;
                if (!player.teleportTo(level, x, MULTI_CLIENT_CONSOLE_POS.getY() + 1.0D,
                        MULTI_CLIENT_CONSOLE_POS.getZ() + 2.5D, Set.<Relative>of(), 180.0F, 0.0F, true)) {
                    throw new AssertionError("Could not restore paired client " + player.getUUID());
                }
            }
            index++;
        }
    }

    private void record(BenchServerContext context, int leases) {
        context.metrics().record(MULTI_CLIENT_PLAYER_COUNT,
                context.server().getPlayerList().getPlayerCount());
        context.metrics().record(MULTI_CLIENT_LEASE_COUNT, leases);
    }
}
