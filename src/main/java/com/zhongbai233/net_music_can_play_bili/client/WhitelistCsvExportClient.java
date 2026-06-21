package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.network.WhitelistCsvExportPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 客户端本地保存服务端下发的白名单 CSV。 */
public final class WhitelistCsvExportClient {
    private WhitelistCsvExportClient() {
    }

    public static void save(WhitelistCsvExportPacket payload) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            Path dir = minecraft.gameDirectory.toPath().resolve("exports").resolve("net_music_can_play_bili");
            Files.createDirectories(dir);
            Path path = dir.resolve(safeFileName(payload.fileName()));
            Files.writeString(path, payload.csv(), StandardCharsets.UTF_8);
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.literal("白名单 CSV 已导出到本地：" + path.toAbsolutePath()));
            }
        } catch (Exception e) {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.literal("白名单 CSV 导出失败：" + e.getMessage()));
            }
        }
    }

    private static String safeFileName(String value) {
        String name = value == null || value.isBlank() ? "net_music_can_play_bili_link_whitelist.csv" : value;
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}