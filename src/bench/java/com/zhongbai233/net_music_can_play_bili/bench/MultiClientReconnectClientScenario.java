package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.MULTI_CLIENT_CONSOLE_POS;

import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

final class MultiClientReconnectClientScenario implements BenchClientScenario {
    private static final int CLIENT_RECONNECT_STABLE_TICKS = 60;
    private static final int SERVER_PROOF_STABLE_TICKS = 20;
    private final int clientIndex = Integer.getInteger("modBench.paired.clientIndex", -1);
    private final int clientCount = Integer.getInteger("modBench.paired.clientCount", -1);
    private UUID originalPlayer;
    private int leaseObservations;
    private int measureTicks;
    private int stableAfterReconnect;
    private boolean disconnectRequested;
    private boolean observedPeerDisconnect;
    private boolean observedReconnect;
    private boolean observedPeerCompletionExit;

    @Override
    public void setup(BenchClientContext context) {
        if (clientCount != 2 || clientIndex < 0 || clientIndex >= clientCount) {
            throw new AssertionError("Invalid reconnect client role " + clientIndex + '/' + clientCount);
        }
        originalPlayer = context.player().getUUID();
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        if (!(context.level().getBlockEntity(MULTI_CLIENT_CONSOLE_POS) instanceof ControlConsoleBlockEntity)
                || context.player().position().distanceTo(Vec3.atCenterOf(MULTI_CLIENT_CONSOLE_POS)) > 8.0D) {
            return BenchClientStepResult.CONTINUE;
        }
        return ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS).registered()
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        var lease = ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS);
        if (onlinePlayers(context) == 2 && lease.active() && lease.leasePresent()) {
            leaseObservations++;
        }
        return leaseObservations >= 10 ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        measureTicks++;
        int online = onlinePlayers(context);
        var lease = ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS);
        if (clientIndex == 0 && !disconnectRequested && measureTicks >= 40) {
            disconnectRequested = true;
            context.minecraft().getConnection().getConnection().disconnect(
                    Component.literal("ModBench planned reconnect"));
            return BenchClientStepResult.CONTINUE;
        }
        if (clientIndex == 1 && online == 1) {
            observedPeerDisconnect = true;
            if (!lease.active() || !lease.leasePresent()) {
                throw new AssertionError("Peer disconnect invalidated surviving formal lease");
            }
        }
        if ((clientIndex == 0 && disconnectRequested || clientIndex == 1 && observedPeerDisconnect)
                && online == 2 && context.player().getUUID().equals(originalPlayer)
                && lease.active() && lease.leasePresent()) {
            observedReconnect = true;
            if (++stableAfterReconnect >= CLIENT_RECONNECT_STABLE_TICKS) {
                return BenchClientStepResult.COMPLETE;
            }
        }
        if (observedReconnect && online == 1
                && stableAfterReconnect >= SERVER_PROOF_STABLE_TICKS
                && lease.active() && lease.leasePresent()) {
            // The peer may finish one render tick earlier. It waited three times the server's
            // proof window before exiting, so the remaining client can now close independently.
            observedPeerCompletionExit = true;
            return BenchClientStepResult.COMPLETE;
        }
        if (measureTicks > 600) {
            throw new AssertionError("Reconnect client stalled: index=" + clientIndex + ", online=" + online
                    + ", disconnect=" + disconnectRequested + ", peerDisconnect=" + observedPeerDisconnect
                    + ", reconnect=" + observedReconnect + ", lease=" + lease);
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (!observedReconnect || stableAfterReconnect < SERVER_PROOF_STABLE_TICKS
                || stableAfterReconnect < CLIENT_RECONNECT_STABLE_TICKS && !observedPeerCompletionExit
                || clientIndex == 0 && !disconnectRequested
                || clientIndex == 1 && !observedPeerDisconnect) {
            throw new AssertionError("Client did not prove planned reconnect: index=" + clientIndex
                    + ", disconnect=" + disconnectRequested + ", peerDisconnect=" + observedPeerDisconnect
                    + ", reconnect=" + observedReconnect + ", stable=" + stableAfterReconnect
                    + ", peerCompletion=" + observedPeerCompletionExit);
        }
    }

    private int onlinePlayers(BenchClientContext context) {
        return context.minecraft().getConnection() == null ? 0
                : context.minecraft().getConnection().getOnlinePlayers().size();
    }
}
