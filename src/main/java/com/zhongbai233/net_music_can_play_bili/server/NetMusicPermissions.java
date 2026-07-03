package com.zhongbai233.net_music_can_play_bili.server;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/** NeoForge 权限节点与命令权限查询入口。 */
public final class NetMusicPermissions {
    public static final PermissionNode<Boolean> AUDIT_SOURCES = booleanNode(
            "audit.sources",
            "NetMusic Bili audit sources",
            "允许查询当前正在播放的现代化唱片机/MP4 音源。",
            NetMusicPermissions::defaultOpLevelTwo);
    public static final PermissionNode<Boolean> PAD_REFRESH = booleanNode(
            "pad.refresh",
            "NetMusic Bili pad refresh",
            "允许刷新 Pad 服务端临时数据。",
            NetMusicPermissions::defaultOpLevelTwo);
    public static final PermissionNode<Boolean> WHITELIST_MANAGE = booleanNode(
            "whitelist.manage",
            "NetMusic Bili whitelist manage",
            "允许管理 Bili/NetMusic 链接白名单并打开审核界面。",
            NetMusicPermissions::defaultOpLevelFour);

    private NetMusicPermissions() {
    }

    public static void onPermissionGather(PermissionGatherEvent.Nodes event) {
        event.addNodes(AUDIT_SOURCES, PAD_REFRESH, WHITELIST_MANAGE);
    }

    public static boolean has(CommandSourceStack source, PermissionNode<Boolean> node) {
        if (source == null) {
            return false;
        }
        try {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                return true;
            }
            if (isSingleplayerOwner(source, player)) {
                return true;
            }
            return PermissionAPI.getPermission(player, node);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static PermissionNode<Boolean> booleanNode(String name, String readableName, String description,
            PermissionNode.PermissionResolver<Boolean> defaultResolver) {
        PermissionNode<Boolean> node = new PermissionNode<>(NetMusicCanPlayBili.MODID, name, PermissionTypes.BOOLEAN,
                defaultResolver);
        node.setInformation(Component.literal(readableName), Component.literal(description));
        return node;
    }

    private static boolean defaultOpLevelTwo(ServerPlayer player, java.util.UUID playerUUID,
            net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext<?>... context) {
        return hasVanillaPermission(player, PermissionLevel.GAMEMASTERS);
    }

    private static boolean defaultOpLevelFour(ServerPlayer player, java.util.UUID playerUUID,
            net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext<?>... context) {
        return hasVanillaPermission(player, PermissionLevel.OWNERS);
    }

    private static boolean hasVanillaPermission(ServerPlayer player, PermissionLevel minimum) {
        if (player == null) {
            return false;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        PermissionLevel actual = server.getProfilePermissions(new NameAndId(player.getGameProfile())).level();
        return permissionRank(actual) >= permissionRank(minimum);
    }

    private static boolean isSingleplayerOwner(CommandSourceStack source, ServerPlayer player) {
        return source.getServer().isSingleplayerOwner(new NameAndId(player.getGameProfile()));
    }

    private static int permissionRank(PermissionLevel level) {
        return switch (level) {
            case MODERATORS -> 1;
            case GAMEMASTERS -> 2;
            case ADMINS -> 3;
            case OWNERS -> 4;
            default -> 0;
        };
    }
}