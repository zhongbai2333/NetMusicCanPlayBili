package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.zhongbai233.net_music_can_play_bili.bili.BiliConfig;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import static net.minecraft.commands.Commands.literal;

/** 客户端命令：/netmusicbili status、/netmusicbili dolby ... */
@EventBusSubscriber(value = Dist.CLIENT)
public final class NetMusicClientCommands {

    private NetMusicClientCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                literal("netmusicbili")
                        .then(literal("status").executes(NetMusicClientCommands::showPlaybackStatus))
                        .then(literal("dolby")
                                .then(literal("joc")
                                        .then(literal("on").executes(ctx -> setJoc(ctx, true)))
                                        .then(literal("off").executes(ctx -> setJoc(ctx, false)))
                                        .then(literal("toggle")
                                                .executes(ctx -> setJoc(ctx, !BiliConfig.dolbyJocEnabled)))
                                        .then(literal("status").executes(NetMusicClientCommands::showJocStatus)))
                                .then(literal("source")
                                        .then(literal("status").executes(NetMusicClientCommands::showSourceStatus)))));
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
}
