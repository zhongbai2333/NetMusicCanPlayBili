package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** B站 登录状态与 Dolby 音效开关持久化。 */
public final class BiliConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("net_music_can_play_bili.json");

    /** Dolby 全景声默认开启，可在电脑界面关闭。 */
    public static volatile boolean dolbyEnabled = true;
    public static volatile boolean dolbyJocEnabled = true;
    public static volatile int dolbyMaxObjectSources = 64;
    public static volatile double stereoCrossfeed = 0.16;

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
                if (root.has("dolbyEnabled")) {
                    dolbyEnabled = root.get("dolbyEnabled").getAsBoolean();
                }
                if (root.has("dolbyJocEnabled")) {
                    dolbyJocEnabled = root.get("dolbyJocEnabled").getAsBoolean();
                }
                if (root.has("dolbyMaxObjectSources")) {
                    dolbyMaxObjectSources = root.get("dolbyMaxObjectSources").getAsInt();
                }
                if (root.has("stereoCrossfeed")) {
                    stereoCrossfeed = root.get("stereoCrossfeed").getAsDouble();
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
            root.addProperty("dolbyEnabled", dolbyEnabled);
            root.addProperty("dolbyJocEnabled", dolbyJocEnabled);
            root.addProperty("dolbyMaxObjectSources", dolbyMaxObjectSources());
            root.addProperty("stereoCrossfeed", stereoCrossfeed);
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_FILE, root.toString());
            LOGGER.info("B站登录状态已保存");
        } catch (IOException e) {
            LOGGER.warn("保存 B站配置失败", e);
        }
    }

    public static float stereoCrossfeedAmount() {
        return (float) Math.max(0.0, Math.min(0.45, stereoCrossfeed));
    }

    public static int dolbyMaxObjectSources() {
        return Math.max(0, Math.min(64, dolbyMaxObjectSources));
    }
}
