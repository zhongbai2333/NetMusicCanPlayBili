package com.zhongbai233.net_music_can_play_bili.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 按客户端玩家 UUID 保存的轻量偏好，不进入世界存档或服务端文档。 */
public final class ClientPlayerPreferences {
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger
            .getLogger(ClientPlayerPreferences.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int SCHEMA_VERSION = 1;

    private final Path file;
    private final Set<UUID> dismissedControlConsoleGuides = new HashSet<>();
    private boolean loaded;

    public ClientPlayerPreferences(Path file) {
        this.file = java.util.Objects.requireNonNull(file, "file");
    }

    public static ClientPlayerPreferences defaults() {
        return DefaultHolder.INSTANCE;
    }

    public synchronized boolean isControlConsoleGuideDismissed(UUID playerId) {
        loadIfNeeded();
        return dismissedControlConsoleGuides.contains(java.util.Objects.requireNonNull(playerId, "playerId"));
    }

    /** 保存失败时仍保留本次客户端进程内的选择，并返回 false 供调用方记录诊断。 */
    public synchronized boolean dismissControlConsoleGuide(UUID playerId) {
        loadIfNeeded();
        if (!dismissedControlConsoleGuides.add(java.util.Objects.requireNonNull(playerId, "playerId"))) {
            return true;
        }
        try {
            saveAtomically();
            return true;
        } catch (IOException exception) {
            LOGGER.log(java.util.logging.Level.WARNING, "保存中控台说明书偏好失败: " + file, exception);
            return false;
        }
    }

    private void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray players = root.has("controlConsoleGuideDismissedPlayers")
                    && root.get("controlConsoleGuideDismissedPlayers").isJsonArray()
                    ? root.getAsJsonArray("controlConsoleGuideDismissedPlayers") : new JsonArray();
            for (JsonElement element : players) {
                try {
                    dismissedControlConsoleGuides.add(UUID.fromString(element.getAsString()));
                } catch (RuntimeException invalidEntry) {
                    LOGGER.warning("忽略无效的中控台说明书玩家 UUID: " + element);
                }
            }
        } catch (Exception exception) {
            dismissedControlConsoleGuides.clear();
            LOGGER.log(java.util.logging.Level.WARNING, "加载客户端玩家偏好失败，将使用默认值: " + file, exception);
        }
    }

    private void saveAtomically() throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("preferences path has no parent: " + file);
        }
        Files.createDirectories(parent);
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray players = new JsonArray();
        dismissedControlConsoleGuides.stream().sorted().forEach(id -> players.add(id.toString()));
        root.add("controlConsoleGuideDismissedPlayers", players);

        Path temporary = Files.createTempFile(parent, file.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static final class DefaultHolder {
        private static final ClientPlayerPreferences INSTANCE = new ClientPlayerPreferences(
                FMLPaths.CONFIGDIR.get().resolve("net_music_can_play_bili")
                        .resolve("client-player-preferences.json"));
    }
}