package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.server.BenchServerContext;
import com.zhongbai233.bench.api.neoforge.server.BenchServerScenario;
import com.zhongbai233.bench.api.neoforge.server.BenchStepResult;
import com.zhongbai233.net_music_can_play_bili.Config;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.NetMusicPermissions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.neoforged.neoforge.server.permission.PermissionAPI;

import java.io.IOException;
import java.util.UUID;

/** Real LuckPerms -> NeoForge PermissionAPI -> Brigadier command-gate integration test. */
final class LuckPermsPermissionBridgeServerScenario implements BenchServerScenario {
    private static final String HANDLER = "luckperms:permission_handler";
    private static final String NODE = "net_music_can_play_bili.whitelist.manage";
    private static final String TARGET = "live:9000000000000999";
    private static final BenchMetricDescriptor BRIDGE_PASSED = new BenchMetricDescriptor(
            "ncpb.permissions.luckperms_bridge", "state", MetricDirection.HIGHER_IS_BETTER);

    private UUID playerId;
    private boolean originalWhitelistEnabled;
    private int phase;
    private int phaseTicks;
    private boolean passed;

    @Override
    public void setup(BenchServerContext context) throws IOException {
        originalWhitelistEnabled = Config.enableLinkWhitelist;
        Config.enableLinkWhitelist = true;
        if (context.server().getPlayerList().getPlayerCount() != 1) {
            throw new AssertionError("LuckPerms bridge Bench requires exactly one remote player, got "
                    + context.server().getPlayerList().getPlayerCount());
        }
        String activeHandler = String.valueOf(PermissionAPI.getActivePermissionHandler());
        if (!HANDLER.equals(activeHandler)) {
            throw new AssertionError("NeoForge did not activate LuckPerms: expected=" + HANDLER
                    + ", actual=" + activeHandler);
        }
        if (!PermissionAPI.getRegisteredNodes().contains(NetMusicPermissions.WHITELIST_MANAGE)) {
            throw new AssertionError("NeoForge did not register permission node " + NODE);
        }

        ServerPlayer player = requirePlayer(context);
        playerId = player.getUUID();
        PermissionLevel vanillaLevel = context.server()
                .getProfilePermissions(new NameAndId(player.getGameProfile())).level();
        if (vanillaLevel == PermissionLevel.OWNERS) {
            throw new AssertionError("LuckPerms bridge player unexpectedly has vanilla owner permission");
        }

        BiliWhitelistManager.remove(context.server(), TARGET);
        runAsConsole(context, "lp user " + playerId + " permission set " + NODE + " false");
    }

    @Override
    public BenchStepResult stabilize(BenchServerContext context) {
        phaseTicks++;
        ServerPlayer player = requirePlayer(context);
        if (!NetMusicPermissions.has(player.createCommandSourceStack(), NetMusicPermissions.WHITELIST_MANAGE)) {
            phaseTicks = 0;
            return BenchStepResult.COMPLETE;
        }
        failIfTimedOut("LuckPerms false assignment", 200);
        return BenchStepResult.CONTINUE;
    }

    @Override
    public BenchStepResult measure(BenchServerContext context) {
        ServerPlayer player = requirePlayer(context);
        phaseTicks++;
        switch (phase) {
            case 0 -> {
                runAsPlayer(context, player, "ncpbs whitelist add " + TARGET);
                nextPhase();
            }
            case 1 -> {
                if (BiliWhitelistManager.isAllowed(context.server(), TARGET)) {
                    throw new AssertionError("Whitelist command bypassed the denied LuckPerms node");
                }
                runAsConsole(context, "lp user " + playerId + " permission set " + NODE + " true");
                nextPhase();
            }
            case 2 -> {
                if (!NetMusicPermissions.has(player.createCommandSourceStack(), NetMusicPermissions.WHITELIST_MANAGE)) {
                    failIfTimedOut("LuckPerms true assignment", 200);
                    return BenchStepResult.CONTINUE;
                }
                runAsPlayer(context, player, "ncpbs whitelist add " + TARGET);
                nextPhase();
            }
            case 3 -> {
                if (!BiliWhitelistManager.isAllowed(context.server(), TARGET)) {
                    failIfTimedOut("authorized whitelist command", 40);
                    return BenchStepResult.CONTINUE;
                }
                runAsConsole(context, "lp user " + playerId + " permission set " + NODE + " false");
                nextPhase();
            }
            case 4 -> {
                if (NetMusicPermissions.has(player.createCommandSourceStack(), NetMusicPermissions.WHITELIST_MANAGE)) {
                    failIfTimedOut("LuckPerms revocation", 200);
                    return BenchStepResult.CONTINUE;
                }
                runAsPlayer(context, player, "ncpbs whitelist remove " + TARGET);
                nextPhase();
            }
            case 5 -> {
                if (!BiliWhitelistManager.isAllowed(context.server(), TARGET)) {
                    throw new AssertionError("Whitelist command bypassed the revoked LuckPerms node");
                }
                passed = true;
                context.metrics().record(BRIDGE_PASSED, 1);
                return BenchStepResult.COMPLETE;
            }
            default -> throw new AssertionError("Unknown LuckPerms bridge phase " + phase);
        }
        return BenchStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchServerContext context) {
        if (!passed || phase != 5 || !HANDLER.equals(String.valueOf(PermissionAPI.getActivePermissionHandler()))) {
            throw new AssertionError("LuckPerms permission bridge did not complete: phase=" + phase
                    + ", passed=" + passed + ", handler=" + PermissionAPI.getActivePermissionHandler());
        }
    }

    @Override
    public void teardown(BenchServerContext context) {
        Config.enableLinkWhitelist = originalWhitelistEnabled;
        try {
            BiliWhitelistManager.remove(context.server(), TARGET);
        } catch (IOException ignored) {
        }
        if (playerId != null) {
            runAsConsole(context, "lp user " + playerId + " permission unset " + NODE);
        }
    }

    private ServerPlayer requirePlayer(BenchServerContext context) {
        ServerPlayer player = playerId == null
                ? context.server().getPlayerList().getPlayers().stream().findFirst().orElse(null)
                : context.server().getPlayerList().getPlayer(playerId);
        if (player == null) {
            throw new AssertionError("LuckPerms bridge remote player disconnected");
        }
        return player;
    }

    private static void runAsConsole(BenchServerContext context, String command) {
        context.server().getCommands().performPrefixedCommand(context.server().createCommandSourceStack(), command);
    }

    private static void runAsPlayer(BenchServerContext context, ServerPlayer player, String command) {
        context.server().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private void nextPhase() {
        phase++;
        phaseTicks = 0;
    }

    private void failIfTimedOut(String operation, int ticks) {
        if (phaseTicks > ticks) {
            throw new AssertionError(operation + " did not converge within " + ticks + " ticks");
        }
    }
}
