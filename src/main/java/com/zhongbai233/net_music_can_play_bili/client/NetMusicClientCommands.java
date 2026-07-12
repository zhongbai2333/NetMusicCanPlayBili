package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.zhongbai233.net_music_can_play_bili.bili.BiliConfig;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache;
import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;
import com.zhongbai233.net_music_can_play_bili.gui.VideoPlaceholderDebugScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** 客户端命令：/netmusicbiliclient status、/ncpbc dolby ... */
@EventBusSubscriber(value = Dist.CLIENT)
public final class NetMusicClientCommands {
    public static final String ROOT_COMMAND = "netmusicbiliclient";
    public static final String SHORT_ROOT_COMMAND = "ncpbc";

    private NetMusicClientCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(clientRoot(ROOT_COMMAND));
        dispatcher.register(clientRoot(SHORT_ROOT_COMMAND));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> clientRoot(String name) {
        return literal(name)
                .then(literal("status").executes(NetMusicClientCommands::showPlaybackStatus))
                .then(hologlassCommands())
                .then(padCommands())
                .then(videoCommands())
                .then(benchCommands())
                .then(dolbyCommands());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> hologlassCommands() {
        return literal("hologlass")
                .then(literal("config").executes(NetMusicClientCommands::openHolographicGlassesConfig))
                .then(literal("test").executes(NetMusicClientCommands::openHolographicScreenConfigTest));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> padCommands() {
        return literal("pad")
                .then(literal("cache")
                        .then(literal("status").executes(NetMusicClientCommands::showPadMapCacheStatus))
                        .then(literal("save").executes(NetMusicClientCommands::savePadMapCache))
                        .then(literal("refresh").executes(NetMusicClientCommands::refreshPadMapCache)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> videoCommands() {
        return literal("video")
                .then(literal("placeholders").executes(NetMusicClientCommands::openVideoPlaceholderDebug));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> benchCommands() {
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

        LiteralArgumentBuilder<CommandSourceStack> videoBench = literal("video")
                .then(literal("cpu-bars")
                        .executes(ctx -> startCpuBarsBench(ctx, false))
                        .then(literal("ignoreSlowFrames")
                                .executes(ctx -> startCpuBarsBench(ctx, true))))
                .then(literal("bili-real")
                        .executes(ctx -> startBiliRealBench(ctx, false))
                        .then(literal("ignoreSlowFrames")
                                .executes(ctx -> startBiliRealBench(ctx, true))));
        return bench.then(videoBench);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> dolbyCommands() {
        return literal("dolby")
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

    private static int openVideoPlaceholderDebug(CommandContext<CommandSourceStack> ctx) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new VideoPlaceholderDebugScreen()));
        feedback(Component.literal("已打开视频占位图调试界面"));
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
        for (String line : ClientAudioOutputRegistry.describeActiveSources()) {
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
                : "播放延迟Bench未开启；runClient 加 -PncpbPlaybackLatencyBench=true"));
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

    private static int startCpuBarsBench(CommandContext<CommandSourceStack> ctx, boolean ignoreSlowFrames) {
        boolean started = VideoRenderStressBench.startCommand(ignoreSlowFrames);
        feedback(Component.literal(started
                ? "CPU彩条视频Bench已启动；" + slowFramePolicy(ignoreSlowFrames)
                : "CPU彩条视频Bench已在运行"));
        return started ? 1 : 0;
    }

    private static int startBiliRealBench(CommandContext<CommandSourceStack> ctx, boolean ignoreSlowFrames) {
        boolean started = BiliRealVideoPlaybackBench.startCommand(ignoreSlowFrames);
        feedback(Component.literal(started
                ? "B站真实解析视频Bench已启动；" + slowFramePolicy(ignoreSlowFrames)
                : "B站真实解析视频Bench已在运行"));
        return started ? 1 : 0;
    }

    private static String slowFramePolicy(boolean ignoreSlowFrames) {
        return ignoreSlowFrames ? "将无视过慢帧继续跑完更高分辨率" : "遇到过慢帧会停止后续更高分辨率";
    }

    private static int showPadMapCacheStatus(CommandContext<CommandSourceStack> ctx) {
        feedback(Component.literal("Pad地图缓存: " + PadMapClientCache.describeStatus()));
        return 1;
    }

    private static int savePadMapCache(CommandContext<CommandSourceStack> ctx) {
        PadMapClientCache.flushDiskCache();
        feedback(Component.literal("Pad地图缓存已尝试落盘: " + PadMapClientCache.diskCachePath()));
        return 1;
    }

    private static int refreshPadMapCache(CommandContext<CommandSourceStack> ctx) {
        int cleared = PadMapClientCache.clearAllCaches(true);
        feedback(Component.literal("Pad地图缓存已刷新: clearedMemoryCells=" + cleared));
        return 1;
    }

}
