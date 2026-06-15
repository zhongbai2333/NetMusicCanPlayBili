package com.zhongbai233.net_music_can_play_bili.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

import java.util.List;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.Commands.argument;

/** 用于管理/审计当前模组播放源的服务端命令。 */
public final class NetMusicBiliServerCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String ROOT_COMMAND = "netmusicbiliaudit";

    private NetMusicBiliServerCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(literal(ROOT_COMMAND)
                .then(literal("audit")
                .then(literal("sources")
                    .then(literal("limit")
                        .then(argument("count", integer(1, 100))
                            .executes(ctx -> listSources(ctx.getSource(), getInteger(ctx, "count")))))
                    .executes(ctx -> listSources(ctx.getSource())))
                        .executes(ctx -> listSources(ctx.getSource())))
                .then(literal("sources")
                .then(literal("limit")
                    .then(argument("count", integer(1, 100))
                        .executes(ctx -> listSources(ctx.getSource(), getInteger(ctx, "count")))))
                        .executes(ctx -> listSources(ctx.getSource()))));
            LOGGER.info("Registered server audit command /{}", ROOT_COMMAND);
    }

    private static boolean isOpOrConsole(CommandSourceStack source) {
        if (source == null) {
            return false;
        }
        try {
            var player = source.getPlayer();
            if (player == null) {
                return true;
            }
            NameAndId profile = new NameAndId(player.getGameProfile());
            return source.getServer().isSingleplayerOwner(profile)
                    || isOpLevelTwoOrHigher(source.getServer().getProfilePermissions(profile).level());
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean isOpLevelTwoOrHigher(PermissionLevel level) {
        return level == PermissionLevel.GAMEMASTERS
                || level == PermissionLevel.ADMINS
                || level == PermissionLevel.OWNERS;
    }

    private static int listSources(CommandSourceStack source) {
        return listSources(source, 20);
    }

    private static int listSources(CommandSourceStack source, int limit) {
        if (!isOpOrConsole(source)) {
            source.sendFailure(Component.literal("需要 OP2 权限才能查询正在播放的音源。")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        List<PlaybackAuditManager.ActiveSource> sources = PlaybackAuditManager.snapshot(source.getServer());
        if (sources.isEmpty()) {
            source.sendSuccess(() -> Component.literal("♫ 当前没有正在播放的现代化唱片机/MP4 音源。")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        int shown = Math.min(Math.max(1, limit), sources.size());
        source.sendSuccess(() -> Component.literal("♫ 正在播放的音源：")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(String.valueOf(sources.size())).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" 个  显示 ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(String.valueOf(shown)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" 个  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("点击坐标可传送").withStyle(ChatFormatting.GRAY)), false);
        for (int i = 0; i < shown; i++) {
            PlaybackAuditManager.ActiveSource active = sources.get(i);
            source.sendSuccess(() -> active.describe(source.getServer()), false);
        }
        if (shown < sources.size()) {
            source.sendSuccess(() -> Component.literal("… 还有 " + (sources.size() - shown)
                    + " 个未显示；用 /" + ROOT_COMMAND + " sources limit <数量> 调整。")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        return sources.size();
    }
}
