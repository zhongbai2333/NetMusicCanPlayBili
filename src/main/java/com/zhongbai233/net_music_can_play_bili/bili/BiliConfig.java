package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * B站登录配置持久化，保存 SESSDATA 到 config 目录。
 */
public final class BiliConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("net_music_can_play_bili.json");

    private BiliConfig() {
    }

    public static void load() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                String content = Files.readString(CONFIG_FILE);
                JsonObject root = JsonParser.parseString(content).getAsJsonObject();
                String sessdata = root.has("sessdata") ? root.get("sessdata").getAsString() : "";
                if (!sessdata.isBlank()) {
                    BiliApiClient.sessdata = sessdata;
                    LOGGER.info("已加载 B站登录状态");
                }
            }
        } catch (Exception e) {
            LOGGER.warn("加载 B站配置失败", e);
        }
    }

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("sessdata", BiliApiClient.sessdata != null ? BiliApiClient.sessdata : "");
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_FILE, root.toString());
            LOGGER.info("B站登录状态已保存");
        } catch (IOException e) {
            LOGGER.warn("保存 B站配置失败", e);
        }
    }
}
