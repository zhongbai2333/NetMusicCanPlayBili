package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.zhongbai233.net_music_can_play_bili.bili.BiliConfig;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** 客户端命令：/netmusicbili status、/netmusicbili dolby ... */
@EventBusSubscriber(value = Dist.CLIENT)
public final class NetMusicClientCommands {

    private NetMusicClientCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> bench = literal("bench")
                .then(literal("status").executes(NetMusicClientCommands::showBenchStatus))
                .then(literal("reset").executes(NetMusicClientCommands::resetBench))
                .then(literal("mark")
                        .then(argument("label", StringArgumentType.greedyString())
                                .executes(ctx -> markBench(ctx, StringArgumentType.getString(ctx, "label"))))
                        .executes(ctx -> markBench(ctx, "manual")))
                .then(literal("perceived")
                        .then(argument("delayMs", IntegerArgumentType.integer(-10_000, 10_000))
                                .then(argument("note", StringArgumentType.greedyString())
                                        .executes(ctx -> perceivedBench(ctx,
                                                IntegerArgumentType.getInteger(ctx, "delayMs"),
                                                StringArgumentType.getString(ctx, "note"))))
                                .executes(ctx -> perceivedBench(ctx,
                                        IntegerArgumentType.getInteger(ctx, "delayMs"), ""))));

        LiteralArgumentBuilder<CommandSourceStack> dolby = literal("dolby")
                .then(literal("joc")
                        .then(literal("on").executes(ctx -> setJoc(ctx, true)))
                        .then(literal("off").executes(ctx -> setJoc(ctx, false)))
                        .then(literal("toggle").executes(ctx -> setJoc(ctx, !BiliConfig.dolbyJocEnabled)))
                        .then(literal("status").executes(NetMusicClientCommands::showJocStatus)))
                .then(literal("objects")
                        .then(literal("status").executes(NetMusicClientCommands::showObjectLimit))
                        .then(argument("count", IntegerArgumentType.integer(0, 64))
                                .executes(ctx -> setObjectLimit(ctx,
                                        IntegerArgumentType.getInteger(ctx, "count")))))
                .then(literal("source")
                        .then(literal("status").executes(NetMusicClientCommands::showSourceStatus)));

        dispatcher.register(literal("netmusicbili")
                .then(literal("status").executes(NetMusicClientCommands::showPlaybackStatus))
                .then(literal("hologlass")
                        .then(literal("config").executes(NetMusicClientCommands::openHolographicGlassesConfig))
                        .then(literal("gui").executes(NetMusicClientCommands::openHolographicScreenConfigTest)))
                .then(bench)
                .then(dolby));
    }

    private static int openHolographicGlassesConfig(CommandContext<CommandSourceStack> ctx) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new HolographicScreenConfigTestScreen(true)));
        feedback(Component.literal("已打开全息眼镜配置界面"));
        return 1;
    }

    private static int openHolographicScreenConfigTest(CommandContext<CommandSourceStack> ctx) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new HolographicScreenConfigTestScreen()));
        feedback(Component.literal("已打开全息屏幕配置测试界面"));
        return 1;
    }

    private static void feedback(Component msg) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(msg);
        }
    }

    private static int setJoc(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        BiliConfig.dolbyJocEnabled = enabled;
        BiliConfig.save();
        feedback(Component.literal(enabled ? "杜比 JOC 对象音频：已启用" : "杜比 JOC 对象音频：已关闭"));
        return 1;
    }

    private static int showJocStatus(CommandContext<CommandSourceStack> ctx) {
        feedback(Component.literal(BiliConfig.dolbyJocEnabled
                ? "杜比 JOC 对象音频：已启用"
                : "杜比 JOC 对象音频：已关闭"));
        return 1;
    }

    private static int setObjectLimit(CommandContext<CommandSourceStack> ctx, int count) {
        BiliConfig.dolbyMaxObjectSources = count;
        BiliConfig.save();
        feedback(Component.literal("Dolby JOC object source limit: " + BiliConfig.dolbyMaxObjectSources()));
        return 1;
    }

    private static int showObjectLimit(CommandContext<CommandSourceStack> ctx) {
        feedback(Component.literal("Dolby JOC object source limit: " + BiliConfig.dolbyMaxObjectSources()));
        return 1;
    }

    private static int showSourceStatus(CommandContext<CommandSourceStack> ctx) {
        for (String line : DolbyAudioRegistry.describeActiveSources()) {
            feedback(Component.literal(line));
        }
        return 1;
    }

    private static int showPlaybackStatus(CommandContext<CommandSourceStack> ctx) {
        for (String line : BiliPlaybackDiagnostics.describeCurrentPlayback()) {
            feedback(Component.literal(line));
        }
        return 1;
    }

    private static int showBenchStatus(CommandContext<CommandSourceStack> ctx) {
        PlaybackLatencyBench.logNow();
        feedback(Component.literal(PlaybackLatencyBench.enabled()
                ? "播放延迟Bench已开启，详细数据已输出到日志"
                : "播放延迟Bench未开启；runClient 加 -PbiliPlaybackLatencyBench=true"));
        return 1;
    }

    private static int resetBench(CommandContext<CommandSourceStack> ctx) {
        PlaybackLatencyBench.reset();
        feedback(Component.literal("播放延迟Bench已重置"));
        return 1;
    }

    private static int markBench(CommandContext<CommandSourceStack> ctx, String label) {
        PlaybackLatencyBench.markUser(label);
        feedback(Component.literal("播放延迟Bench标记: " + label));
        return 1;
    }

    private static int perceivedBench(CommandContext<CommandSourceStack> ctx, int delayMs, String note) {
        PlaybackLatencyBench.recordPerceivedDelay(delayMs, note);
        feedback(Component.literal("播放延迟Bench听感记录: " + delayMs + "ms"));
        return 1;
    }

}
