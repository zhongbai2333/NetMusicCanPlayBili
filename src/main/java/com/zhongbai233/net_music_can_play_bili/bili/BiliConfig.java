package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/** B站普通客户端设置，以及与其物理隔离的登录凭据加载入口。 */
public final class BiliConfig {
    private static final Logger LOGGER = Logger.getLogger(BiliConfig.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Object IO_LOCK = new Object();
    private static final String LEGACY_SESSDATA = "sessdata";
    private static final String LEGACY_WEB_COOKIE = "webCookie";

    /** Dolby 全景声默认开启，可在电脑界面关闭。 */
    public static volatile boolean dolbyEnabled = true;
    public static volatile boolean dolbyJocEnabled = true;
    public static volatile int dolbyMaxObjectSources = 64;
    public static volatile double stereoCrossfeed = 0.16;
    /** 可手动粘贴浏览器 navigator.userAgent，用于规避 B站 CDN 对旧 UA/异常 UA 的风控。 */
    public static volatile String userAgent = BiliApiProperties.initialUserAgent();
    /** 诊断开关：为空 userAgent 时进程启动随机选一个桌面 UA；B站 CDN 频繁 403 后切换到下一个。 */
    public static volatile boolean rotateUserAgent = BiliApiProperties.rotateUserAgent();

    private BiliConfig() {
    }

    public static void load() {
        BiliCredentialStore.Credentials runtimeCredentials = new BiliCredentialStore.Credentials(
                BiliApiProperties.initialSessdata(), BiliApiProperties.initialWebCookie());
        load(SettingsHolder.CONFIG_FILE, CredentialHolder.STORE, runtimeCredentials);
    }

    static void load(Path settingsFile, BiliCredentialStore credentialStore,
            BiliCredentialStore.Credentials runtimeCredentials) {
        java.util.Objects.requireNonNull(settingsFile, "settingsFile");
        java.util.Objects.requireNonNull(credentialStore, "credentialStore");
        java.util.Objects.requireNonNull(runtimeCredentials, "runtimeCredentials");
        synchronized (IO_LOCK) {
            loadSettingsAndScrubLegacyCredentials(settingsFile);
            loadCredentials(credentialStore, runtimeCredentials);
        }
    }

    private static void loadSettingsAndScrubLegacyCredentials(Path settingsFile) {
        if (!Files.isRegularFile(settingsFile)) {
            return;
        }
        String content;
        try {
            content = Files.readString(settingsFile, StandardCharsets.UTF_8);
        } catch (IOException error) {
            LOGGER.log(Level.WARNING, "读取 B站普通设置失败，未加载该文件: " + settingsFile, error);
            return;
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException invalidSettings) {
            rebuildInvalidSettings(settingsFile, invalidSettings);
            return;
        }

        try {
            boolean containedLegacyCredentials = root.remove(LEGACY_SESSDATA) != null;
            containedLegacyCredentials |= root.remove(LEGACY_WEB_COOKIE) != null;
            if (containedLegacyCredentials) {
                try {
                    writeJsonAtomically(settingsFile, root);
                    LOGGER.warning("检测到旧版配置中的明文 B站 Cookie，已从 " + settingsFile
                            + " 清除。为防止整合包分发泄漏，旧 Cookie 不会自动迁移，请重新扫码登录");
                } catch (IOException scrubError) {
                    LOGGER.log(Level.SEVERE, "检测到旧版配置中的明文 B站 Cookie，但无法从 " + settingsFile
                            + " 清除。该 Cookie 不会被加载；请手动删除 sessdata/webCookie 字段并重新扫码登录",
                            scrubError);
                }
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
            if (root.has("userAgent")) {
                String configuredUserAgent = root.get("userAgent").getAsString();
                if (!configuredUserAgent.isBlank()) {
                    userAgent = configuredUserAgent;
                }
            }
            if (root.has("rotateUserAgent")) {
                rotateUserAgent = root.get("rotateUserAgent").getAsBoolean();
            }
            BiliCdnSelector.load(root);
        } catch (RuntimeException invalidSettings) {
            rebuildInvalidSettings(settingsFile, invalidSettings);
        }
    }

    private static void rebuildInvalidSettings(Path settingsFile, RuntimeException invalidSettings) {
        LOGGER.log(Level.WARNING, "B站普通设置无效、已截断或不是 JSON 对象；将以当前非敏感设置原子重建: "
                + settingsFile, invalidSettings);
        try {
            JsonObject cleanRoot = new JsonObject();
            populateSettings(cleanRoot);
            writeJsonAtomically(settingsFile, cleanRoot);
            LOGGER.warning("已重建损坏的 B站普通设置，原文件中的潜在明文 Cookie 已清除: " + settingsFile);
        } catch (IOException | RuntimeException rebuildError) {
            LOGGER.log(Level.SEVERE, "无法原子重建损坏的 B站普通设置，磁盘上的原文件可能仍含明文 Cookie；"
                    + "其中的 Cookie 未加载，请在打包整合包前手动删除该文件: " + settingsFile, rebuildError);
        }
    }

    private static void loadCredentials(BiliCredentialStore credentialStore,
            BiliCredentialStore.Credentials runtimeCredentials) {
        if (hasCredentials(runtimeCredentials)) {
            applyCredentials(runtimeCredentials);
            LOGGER.info("已从 JVM 参数加载仅本次运行使用的 B站登录状态");
            return;
        }

        BiliCredentialStore.LoadResult result = credentialStore.load();
        if (result.loaded()) {
            BiliCredentialStore.Credentials credentials = result.credentials().orElseThrow();
            applyCredentials(credentials);
            LOGGER.info("已从本机加密凭据库加载 B站登录状态");
            return;
        }

        clearCredentials();
        switch (result.status()) {
            case ABSENT -> {
                // 首次运行或尚未扫码，无需输出警告。
            }
            case MACHINE_ID_UNAVAILABLE -> LOGGER.warning("无法取得稳定机器标识，未加载 B站登录状态；请重新扫码，"
                    + "凭据只能在本次游戏中使用");
            case INVALID_OR_WRONG_MACHINE -> LOGGER.warning("B站加密登录凭据已损坏或来自另一台机器，已拒绝加载；"
                    + "请在本机重新扫码登录");
            case IO_ERROR -> LOGGER.warning("读取 B站加密登录凭据失败，已按未登录状态启动；请检查游戏目录权限后重新扫码");
            case LOADED -> throw new IllegalStateException("loaded credential result did not contain credentials");
        }
    }

    private static boolean hasCredentials(BiliCredentialStore.Credentials credentials) {
        return !credentials.sessdata().isBlank() || !credentials.webCookie().isBlank();
    }

    private static void applyCredentials(BiliCredentialStore.Credentials credentials) {
        String sessdata = credentials.sessdata().trim();
        String webCookie = credentials.webCookie().trim();
        if (sessdata.isBlank()) {
            sessdata = cookieValue(webCookie, "SESSDATA");
        }
        if (webCookie.isBlank() && !sessdata.isBlank()) {
            webCookie = "SESSDATA=" + sessdata;
        }
        BiliApiClient.sessdata = sessdata;
        BiliApiClient.webCookie = webCookie;
    }

    private static String cookieValue(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return "";
        }
        for (String part : cookieHeader.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0 && name.equals(part.substring(0, equals).trim())) {
                return part.substring(equals + 1).trim();
            }
        }
        return "";
    }

    private static void clearCredentials() {
        BiliApiClient.sessdata = "";
        BiliApiClient.webCookie = "";
    }

    /** 同步、原子地保存普通设置；此路径永远不会读写登录凭据库。 */
    public static void save() {
        if (!save(SettingsHolder.CONFIG_FILE)) {
            LOGGER.warning("保存 B站普通设置失败");
        }
    }

    /** 显式保存当前进程中的登录凭据；普通设置保存不会调用此方法。 */
    public static BiliCredentialStore.SaveResult saveCredentials() {
        return saveCredentials(CredentialHolder.STORE, new BiliCredentialStore.Credentials(
                BiliApiClient.sessdata, BiliApiClient.webCookie));
    }

    static BiliCredentialStore.SaveResult saveCredentials(BiliCredentialStore credentialStore,
            BiliCredentialStore.Credentials credentials) {
        java.util.Objects.requireNonNull(credentialStore, "credentialStore");
        java.util.Objects.requireNonNull(credentials, "credentials");
        return credentialStore.save(credentials);
    }

    static boolean save(Path settingsFile) {
        java.util.Objects.requireNonNull(settingsFile, "settingsFile");
        synchronized (IO_LOCK) {
            try {
                JsonObject root = readSettingsRootForSave(settingsFile);
                populateSettings(root);
                writeJsonAtomically(settingsFile, root);
                LOGGER.fine("B站普通设置已保存");
                return true;
            } catch (IOException error) {
                LOGGER.log(Level.WARNING, "保存 B站普通设置失败: " + settingsFile, error);
                return false;
            }
        }
    }

    private static void populateSettings(JsonObject root) {
        root.remove(LEGACY_SESSDATA);
        root.remove(LEGACY_WEB_COOKIE);
        root.addProperty("dolbyEnabled", dolbyEnabled);
        root.addProperty("dolbyJocEnabled", dolbyJocEnabled);
        root.addProperty("dolbyMaxObjectSources", dolbyMaxObjectSources());
        root.addProperty("stereoCrossfeed", stereoCrossfeed);
        root.addProperty("userAgent", BiliConfig.userAgent != null ? BiliConfig.userAgent : "");
        root.addProperty("rotateUserAgent", rotateUserAgent);
        BiliCdnSelector.save(root);
    }

    private static JsonObject readSettingsRootForSave(Path settingsFile) throws IOException {
        if (!Files.isRegularFile(settingsFile)) {
            return new JsonObject();
        }
        String content = Files.readString(settingsFile, StandardCharsets.UTF_8);
        try {
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException invalidSettings) {
            LOGGER.log(Level.WARNING, "B站普通设置不是有效 JSON，将以当前内存设置重建: " + settingsFile,
                    invalidSettings);
            return new JsonObject();
        }
    }

    private static void writeJsonAtomically(Path target, JsonObject root) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("settings path has no parent: " + target);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static float stereoCrossfeedAmount() {
        return (float) Math.max(0.0, Math.min(0.45, stereoCrossfeed));
    }

    public static int dolbyMaxObjectSources() {
        return Math.max(0, Math.min(64, dolbyMaxObjectSources));
    }

    private static final class SettingsHolder {
        private static final Path CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("net_music_can_play_bili.json");

        private SettingsHolder() {
        }
    }

    private static final class CredentialHolder {
        private static final BiliCredentialStore STORE = BiliCredentialStore.defaults();

        private CredentialHolder() {
        }
    }
}
