package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.MULTI_CLIENT_CONSOLE_POS;

import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import net.minecraft.world.phys.Vec3;

final class MultiClientConsumerClientScenario implements BenchClientScenario {
    private final int clientIndex = Integer.getInteger("modBench.paired.clientIndex", -1);
    private final int clientCount = Integer.getInteger("modBench.paired.clientCount", -1);
    private boolean observedBothPlayers;
    private boolean observedPeerDisconnect;
    private int leaseObservations;
    private int measureTicks;
    private int loneTicks;

    @Override
    public void setup(BenchClientContext context) {
        if (clientCount != 2 || clientIndex < 0 || clientIndex >= clientCount) {
            throw new AssertionError("Invalid paired client role " + clientIndex + "/" + clientCount);
        }
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
        if (clientIndex == 0) {
            if (observedBothPlayers && measureTicks >= 80) {
                return BenchClientStepResult.COMPLETE;
            }
        } else if (observedBothPlayers && online == 1) {
            observedPeerDisconnect = true;
            if (++loneTicks >= 60) {
                return BenchClientStepResult.COMPLETE;
            }
        }
        if (measureTicks > 600) {
            throw new AssertionError("Paired client stalled: index=" + clientIndex + ", online=" + online
                    + ", leaseObservations=" + leaseObservations + ", observedBoth=" + observedBothPlayers
                    + ", observedPeerExit=" + observedPeerDisconnect);
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (!observedBothPlayers || leaseObservations < 5
                || (clientIndex == 1 && !observedPeerDisconnect)) {
            throw new AssertionError("Paired client did not observe its ownership lifecycle: index="
                    + clientIndex + ", leaseObservations=" + leaseObservations + ", both=" + observedBothPlayers
                    + ", peerExit=" + observedPeerDisconnect);
        }
    }

    private int onlinePlayers(BenchClientContext context) {
        return context.minecraft().getConnection() == null ? 0
                : context.minecraft().getConnection().getOnlinePlayers().size();
    }
}
