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

import java.util.Set;
import java.util.UUID;

final class MultiClientReconnectServerScenario implements BenchServerScenario {
    private final Set<UUID> initialPlayers = new java.util.HashSet<>();
    private UUID reconnectingPlayer;
    private int stableBeforeDisconnect;
    private int stableAfterReconnect;
    private int measureTicks;
    private boolean observedDisconnect;
    private boolean observedSameIdentityReconnect;

    @Override
    public void setup(BenchServerContext context) {
        if (context.server().getPlayerList().getPlayerCount() != 2) {
            throw new AssertionError("Expected two clients for reconnect, got "
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
            throw new AssertionError("Reconnect console block entity was not created");
        }
        console.linkTo(level.dimension().identifier().toString(), MULTI_CLIENT_SOURCE_POS);
        int index = 0;
        for (ServerPlayer player : context.server().getPlayerList().getPlayers()) {
            initialPlayers.add(player.getUUID());
            if (player.getGameProfile().name().equals("ModBenchClient0")) {
                reconnectingPlayer = player.getUUID();
            }
            if (!player.teleportTo(level, MULTI_CLIENT_CONSOLE_POS.getX() + 0.25D + index++ * 0.5D,
                    MULTI_CLIENT_CONSOLE_POS.getY() + 1.0D, MULTI_CLIENT_CONSOLE_POS.getZ() + 2.5D,
                    Set.<Relative>of(), 180.0F, 0.0F, true)) {
                throw new AssertionError("Could not place reconnect client " + player.getUUID());
            }
        }
        if (initialPlayers.size() != 2 || reconnectingPlayer == null) {
            throw new AssertionError("Reconnect fixture did not identify both stable clients: "
                    + initialPlayers + ", reconnecting=" + reconnectingPlayer);
        }
    }

    @Override
    public BenchStepResult stabilize(BenchServerContext context) {
        Set<UUID> leases = activePlayers(context);
        record(context, leases.size());
        return leases.equals(initialPlayers) && context.server().getPlayerList().getPlayerCount() == 2
                ? BenchStepResult.COMPLETE : BenchStepResult.CONTINUE;
    }

    @Override
    public BenchStepResult warmup(BenchServerContext context) {
        Set<UUID> leases = activePlayers(context);
        record(context, leases.size());
        if (!leases.equals(initialPlayers) || context.server().getPlayerList().getPlayerCount() != 2) {
            stableBeforeDisconnect = 0;
            return BenchStepResult.CONTINUE;
        }
        return ++stableBeforeDisconnect >= 20 ? BenchStepResult.COMPLETE : BenchStepResult.CONTINUE;
    }

    @Override
    public BenchStepResult measure(BenchServerContext context) {
        measureTicks++;
        Set<UUID> online = context.server().getPlayerList().getPlayers().stream()
                .map(ServerPlayer::getUUID).collect(java.util.stream.Collectors.toSet());
        Set<UUID> leases = activePlayers(context);
        record(context, leases.size());
        if (!observedDisconnect && online.size() == 1 && !online.contains(reconnectingPlayer)
                && leases.equals(online)) {
            observedDisconnect = true;
        } else if (observedDisconnect && online.equals(initialPlayers) && leases.equals(initialPlayers)) {
            observedSameIdentityReconnect = true;
            if (++stableAfterReconnect >= 20) {
                return BenchStepResult.COMPLETE;
            }
        } else if (observedSameIdentityReconnect) {
            stableAfterReconnect = 0;
        }
        if (measureTicks > 600) {
            throw new AssertionError("Reconnect lifecycle stalled: online=" + online + ", leases=" + leases
                    + ", missingObserved=" + observedDisconnect + ", rejoined="
                    + observedSameIdentityReconnect);
        }
        return BenchStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchServerContext context) {
        if (!observedDisconnect || !observedSameIdentityReconnect || stableAfterReconnect < 20) {
            throw new AssertionError("Server did not prove same-identity reconnect and lease reacquisition");
        }
    }

    @Override
    public void teardown(BenchServerContext context) {
        // A paired server remains alive after writing its report while both clients finish their
        // independent proof windows. Clearing leases or removing the platform here would alter
        // their workload (and can kill the players). The paired coordinator terminates this
        // temporary server after all three reports exist; its run directory is recreated next run.
    }

    private Set<UUID> activePlayers(BenchServerContext context) {
        return ControlConsoleConsumerLeaseRegistry.activePlayers(
                context.level().dimension().identifier().toString(), MULTI_CLIENT_CONSOLE_POS.asLong(),
                System.currentTimeMillis());
    }

    private void record(BenchServerContext context, int leases) {
        context.metrics().record(MULTI_CLIENT_PLAYER_COUNT,
                context.server().getPlayerList().getPlayerCount());
        context.metrics().record(MULTI_CLIENT_LEASE_COUNT, leases);
    }
}
