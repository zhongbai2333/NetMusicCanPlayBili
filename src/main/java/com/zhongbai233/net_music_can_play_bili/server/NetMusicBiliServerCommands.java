package com.zhongbai233.net_music_can_play_bili.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistCsvExportPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.Commands.argument;

/** 用于管理/审计当前模组播放源的服务端命令。 */
public final class NetMusicBiliServerCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String ROOT_COMMAND = "netmusicbiliserver";
    public static final String SHORT_ROOT_COMMAND = "ncpbs";

    private NetMusicBiliServerCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(serverRoot(ROOT_COMMAND));
        dispatcher.register(serverRoot(SHORT_ROOT_COMMAND));
        LOGGER.info("Registered server commands /{} and /{}", ROOT_COMMAND, SHORT_ROOT_COMMAND);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> serverRoot(String name) {
        return literal(name)
                .then(literal("sources")
                .requires(NetMusicBiliServerCommands::canAuditSources)
                        .then(literal("limit")
                                .then(argument("count", integer(1, 100))
                                        .executes(ctx -> listSources(ctx.getSource(), getInteger(ctx, "count")))))
                        .executes(ctx -> listSources(ctx.getSource())))
                .then(literal("pad")
                .requires(NetMusicBiliServerCommands::canRefreshPad)
                .then(literal("refresh")
                    .executes(ctx -> refreshPadServerState(ctx.getSource()))))
                .then(literal("whitelist")
                .requires(NetMusicBiliServerCommands::canManageWhitelist)
                        .then(literal("add")
                                .then(argument("idOrLink", StringArgumentType.greedyString())
                                        .executes(ctx -> addWhitelist(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "idOrLink")))))
                        .then(literal("list")
                                .executes(ctx -> listWhitelist(ctx.getSource())))
                        .then(literal("remove")
                                .then(argument("idOrLink", StringArgumentType.greedyString())
                                        .executes(ctx -> removeWhitelist(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "idOrLink")))))
                        .then(literal("export")
                                .executes(ctx -> exportWhitelist(ctx.getSource())))
                        .then(literal("review")
                                .executes(ctx -> openWhitelistReview(ctx.getSource()))));
    }

    private static int refreshPadServerState(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Pad服务端临时状态已刷新。客户端地图缓存请使用 /ncpbc pad cache refresh。")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static boolean canAuditSources(CommandSourceStack source) {
        return NetMusicPermissions.has(source, NetMusicPermissions.AUDIT_SOURCES);
    }

    private static boolean canRefreshPad(CommandSourceStack source) {
        return NetMusicPermissions.has(source, NetMusicPermissions.PAD_REFRESH);
    }

    public static boolean canManageWhitelist(CommandSourceStack source) {
        return NetMusicPermissions.has(source, NetMusicPermissions.WHITELIST_MANAGE);
    }

    private static int listSources(CommandSourceStack source) {
        return listSources(source, 20);
    }

    private static int listSources(CommandSourceStack source, int limit) {
        if (!canAuditSources(source)) {
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

    private static int addWhitelist(CommandSourceStack source, String raw) {
        try {
            BiliWhitelistManager.AddResult result = BiliWhitelistManager.add(source.getServer(), raw,
                    source.getPlayer());
            return switch (result.status()) {
                case ADDED -> {
                    BiliWhitelistManager.Entry entry = result.entry();
                    source.sendSuccess(() -> Component.literal("已添加链接白名单：")
                            .withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(entry.id).withStyle(ChatFormatting.YELLOW))
                            .append(Component.literal("  添加者：" + entry.addedByName).withStyle(ChatFormatting.GRAY)),
                            true);
                    yield 1;
                }
                case DUPLICATE -> {
                    BiliWhitelistManager.Entry entry = result.entry();
                    source.sendFailure(Component.literal("该条目已在白名单：" + entry.id
                            + "（添加者：" + entry.addedByName + "，时间：" + entry.addedAt + "）")
                            .withStyle(ChatFormatting.YELLOW));
                    yield 0;
                }
                case INVALID -> {
                    source.sendFailure(Component.literal("请输入 BV号、av号，或 NetMusic 电脑使用的第三方链接/URL。")
                            .withStyle(ChatFormatting.RED));
                    yield 0;
                }
            };
        } catch (IOException e) {
            LOGGER.warn("保存链接白名单失败", e);
            source.sendFailure(Component.literal("保存白名单失败：" + e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int listWhitelist(CommandSourceStack source) {
        List<BiliWhitelistManager.Entry> entries = BiliWhitelistManager.entries(source.getServer());
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("链接白名单为空。启用配置后，未加入白名单的 BV/第三方链接不能创建唱片或音源头。")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("链接白名单：")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(String.valueOf(entries.size())).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" 条").withStyle(ChatFormatting.GRAY)), false);
        for (int i = 0; i < entries.size(); i++) {
            int index = i + 1;
            BiliWhitelistManager.Entry entry = entries.get(i);
            source.sendSuccess(() -> Component.literal(index + ". ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("[" + entry.type + "] ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(entry.id).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("  by " + entry.addedByName).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("  " + entry.addedAt).withStyle(ChatFormatting.DARK_GRAY)), false);
        }
        return entries.size();
    }

    private static int removeWhitelist(CommandSourceStack source, String raw) {
        try {
            BiliWhitelistManager.RemoveResult result = BiliWhitelistManager.remove(source.getServer(), raw);
            return switch (result.status()) {
                case REMOVED -> {
                    source.sendSuccess(() -> Component.literal("已删除链接白名单：")
                            .withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(result.entry().id).withStyle(ChatFormatting.YELLOW)), true);
                    yield 1;
                }
                case MISSING -> {
                    source.sendFailure(Component.literal("白名单中没有：" + result.requestedId())
                            .withStyle(ChatFormatting.YELLOW));
                    yield 0;
                }
                case INVALID -> {
                    source.sendFailure(Component.literal("请输入 BV号、av号，或 NetMusic 电脑使用的第三方链接/URL。")
                            .withStyle(ChatFormatting.RED));
                    yield 0;
                }
            };
        } catch (IOException e) {
            LOGGER.warn("保存链接白名单失败", e);
            source.sendFailure(Component.literal("保存白名单失败：" + e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int exportWhitelist(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("CSV 导出需要由玩家执行，才能下载到本地客户端。")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        String csv = BiliWhitelistManager.exportCsv(source.getServer());
        PacketDistributor.sendToPlayer(player, WhitelistCsvExportPacket.create(csv));
        source.sendSuccess(() -> Component.literal("已发送白名单 CSV 到你的客户端，将保存到本地游戏目录。")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int openWhitelistReview(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("白名单审核 GUI 需要由玩家执行。")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        WhitelistReviewPacket.sendTo(player);
        source.sendSuccess(() -> Component.literal("已打开白名单审核界面。")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }
}
