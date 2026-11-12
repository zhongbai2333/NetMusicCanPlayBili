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

/**
 * Client half of the paired console-lease lifecycle. Client 0 drops its connection, is rejoined by the
 * paired harness with the same UUID and must reacquire its formal lease before it exits; client 1 must keep
 * its own lease through the drop, observe the rejoin, then survive client 0's final exit alone.
 */
final class MultiClientConsumerClientScenario implements BenchClientScenario {
    private static final int DISCONNECT_AFTER_TICKS = 40;
    private static final int CLIENT_RECONNECT_STABLE_TICKS = 60;
    private static final int SERVER_PROOF_STABLE_TICKS = 20;
    private final int clientIndex = Integer.getInteger("modBench.paired.clientIndex", -1);
    private final int clientCount = Integer.getInteger("modBench.paired.clientCount", -1);
    private UUID originalPlayer;
    private boolean observedBothPlayers;
    private boolean disconnectRequested;
    private boolean observedReconnectDrop;
    private boolean observedReconnect;
    private boolean observedPeerDisconnect;
    private int leaseObservations;
    private int measureTicks;
    private int stableAfterReconnect;
    private int loneTicks;
    private int loneLeaseTicks;

    @Override
    public void setup(BenchClientContext context) {
        if (clientCount != 2 || clientIndex < 0 || clientIndex >= clientCount) {
            throw new AssertionError("Invalid paired client role " + clientIndex + "/" + clientCount);
        }
        originalPlayer = context.player().getUUID();
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        if (!(context.level().getBlockEntity(MULTI_CLIENT_CONSOLE_POS) instanceof ControlConsoleBlockEntity)
                || context.minecraft().player == null
                || context.minecraft().player.position().distanceTo(
                        Vec3.atCenterOf(MULTI_CLIENT_CONSOLE_POS)) > 8.0D) {
            return BenchClientStepResult.CONTINUE;
        }
        return ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS).registered()
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        int online = onlinePlayers(context);
        observedBothPlayers |= online == 2;
        var lease = ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS);
        if (lease.active() && lease.leasePresent()) {
            leaseObservations++;
        }
        return observedBothPlayers && leaseObservations >= 5
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        measureTicks++;
        int online = onlinePlayers(context);
        observedBothPlayers |= online == 2;
        var lease = ControlConsoleRenderer.consumerLeaseDiagnostic(MULTI_CLIENT_CONSOLE_POS);
        if (lease.active() && lease.leasePresent()) {
            leaseObservations++;
        }
        boolean leaseHeld = lease.active() && lease.leasePresent();
        if (clientIndex == 0) {
            if (!disconnectRequested && observedBothPlayers && measureTicks >= DISCONNECT_AFTER_TICKS) {
                disconnectRequested = true;
                context.minecraft().getConnection().getConnection().disconnect(
                        Component.literal("ModBench planned reconnect"));
                return BenchClientStepResult.CONTINUE;
            }
            if (disconnectRequested && online == 2 && context.player().getUUID().equals(originalPlayer)
                    && leaseHeld) {
                observedReconnect = true;
                if (++stableAfterReconnect >= CLIENT_RECONNECT_STABLE_TICKS) {
                    return BenchClientStepResult.COMPLETE;
                }
            }
        } else if (!observedReconnect) {
            if (observedBothPlayers && online == 1) {
                observedReconnectDrop = true;
                if (!leaseHeld) {
                    throw new AssertionError("Peer disconnect invalidated surviving formal lease: " + lease);
                }
            } else if (observedReconnectDrop && online == 2 && leaseHeld) {
                observedReconnect = true;
                stableAfterReconnect++;
            }
        } else if (online == 2 && leaseHeld) {
            stableAfterReconnect++;
        } else if (online == 1 && stableAfterReconnect >= SERVER_PROOF_STABLE_TICKS) {
            observedPeerDisconnect = true;
            // The surviving player must keep its own formal lease for the whole lone phase; losing it here
            // used to pass because only the online count was checked.
            if (!leaseHeld) {
                throw new AssertionError("Surviving formal lease lost while alone: " + lease);
            }
            loneLeaseTicks++;
            if (++loneTicks >= 60) {
                return BenchClientStepResult.COMPLETE;
            }
        }
        if (measureTicks > 1_200) {
            throw new AssertionError("Paired client stalled: index=" + clientIndex + ", online=" + online
                    + ", leaseObservations=" + leaseObservations + ", observedBoth=" + observedBothPlayers
                    + ", disconnect=" + disconnectRequested + ", reconnectDrop=" + observedReconnectDrop
                    + ", reconnect=" + observedReconnect + ", stable=" + stableAfterReconnect
                    + ", observedPeerExit=" + observedPeerDisconnect + ", lease=" + lease);
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (!observedBothPlayers || leaseObservations < 5 || !observedReconnect
                || (clientIndex == 1 && loneLeaseTicks < SERVER_PROOF_STABLE_TICKS)
                || stableAfterReconnect < SERVER_PROOF_STABLE_TICKS
                || (clientIndex == 0 && !disconnectRequested)
                || (clientIndex == 1 && (!observedReconnectDrop || !observedPeerDisconnect))) {
            throw new AssertionError("Paired client did not observe its ownership lifecycle: index="
                    + clientIndex + ", leaseObservations=" + leaseObservations + ", both=" + observedBothPlayers
                    + ", disconnect=" + disconnectRequested + ", reconnectDrop=" + observedReconnectDrop
                    + ", reconnect=" + observedReconnect + ", stable=" + stableAfterReconnect
                    + ", loneLeaseTicks=" + loneLeaseTicks
                    + ", peerExit=" + observedPeerDisconnect);
        }
    }

    private int onlinePlayers(BenchClientContext context) {
        return context.minecraft().getConnection() == null ? 0
                : context.minecraft().getConnection().getOnlinePlayers().size();
    }
}
