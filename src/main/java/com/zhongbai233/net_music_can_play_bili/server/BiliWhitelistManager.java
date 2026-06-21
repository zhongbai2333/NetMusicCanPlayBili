package com.zhongbai233.net_music_can_play_bili.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.Config;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 服务端 BV/第三方链接白名单，按世界存档持久化。 */
public final class BiliWhitelistManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String DATA_FILE = NetMusicCanPlayBili.MODID + "_link_whitelist.json";

    private static Path loadedPath;
    private static WhitelistData data = new WhitelistData();

    private BiliWhitelistManager() {
    }

    public static boolean enabled() {
        return Config.enableLinkWhitelist;
    }

    public static synchronized Optional<String> canonicalId(String raw) {
        return canonicalResource(raw).map(resource -> resource.key());
    }

    public static synchronized Optional<CanonicalResource> canonicalResource(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        Optional<CanonicalResource> canonicalId = canonicalPrefixedResource(trimmed);
        if (canonicalId.isPresent()) {
            return canonicalId;
        }
        BiliApiClient.VideoSelection selection = BiliApiClient.extractVideoSelectionLenientWithShortLink(raw);
        if (selection != null) {
            return Optional.of(new CanonicalResource("bili", normalizeVideoSelection(selection)));
        }
        String normalizedUrl = normalizeUrl(trimmed);
        if (normalizedUrl == null || normalizedUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new CanonicalResource("url", normalizedUrl));
    }

    private static Optional<CanonicalResource> canonicalPrefixedResource(String raw) {
        int split = raw.indexOf(':');
        if (split <= 0) {
            return Optional.empty();
        }
        String type = raw.substring(0, split).toLowerCase(java.util.Locale.ROOT);
        String value = raw.substring(split + 1).trim();
        if (value.isBlank()) {
            return Optional.empty();
        }
        if ("bili".equals(type)) {
            BiliApiClient.VideoSelection selection = BiliApiClient.extractVideoSelectionLenientWithShortLink(value);
            return selection != null ? Optional.of(new CanonicalResource("bili", normalizeVideoSelection(selection)))
                    : Optional.empty();
        }
        if ("url".equals(type)) {
            String normalizedUrl = normalizeUrl(value);
            return normalizedUrl == null || normalizedUrl.isBlank()
                    ? Optional.empty()
                    : Optional.of(new CanonicalResource("url", normalizedUrl));
        }
        return Optional.empty();
    }

    public static synchronized boolean isAllowed(MinecraftServer server, String raw) {
        if (!enabled()) {
            return true;
        }
        Optional<CanonicalResource> canonical = canonicalResource(raw);
        return canonical.isPresent() && isAllowedCanonical(server, canonical.get().key());
    }

    public static synchronized boolean isAllowedCanonical(MinecraftServer server, String canonicalId) {
        if (!enabled()) {
            return true;
        }
        ensureLoaded(server);
        String key = storageKey(canonicalId);
        return data.entries.containsKey(key);
    }

    public static synchronized AddResult add(MinecraftServer server, String raw, ServerPlayer player)
            throws IOException {
        ensureLoaded(server);
        Optional<CanonicalResource> canonical = canonicalResource(raw);
        if (canonical.isEmpty()) {
            return AddResult.invalid();
        }

        String key = storageKey(canonical.get().key());
        Entry previous = data.entries.get(key);
        if (previous != null) {
            return AddResult.duplicate(previous);
        }

        Entry entry = new Entry();
        entry.id = canonical.get().key();
        entry.type = canonical.get().type();
        entry.originalInput = raw == null ? "" : raw.trim();
        entry.addedByName = player != null ? player.getDisplayName().getString() : "Console";
        UUID uuid = player != null ? player.getUUID() : null;
        entry.addedByUuid = uuid != null ? uuid.toString() : "";
        entry.addedAt = Instant.now().toString();
        data.entries.put(key, entry);
        save(server);
        return AddResult.added(entry);
    }

    public static synchronized RemoveResult remove(MinecraftServer server, String raw) throws IOException {
        ensureLoaded(server);
        Optional<CanonicalResource> canonical = canonicalResource(raw);
        if (canonical.isEmpty()) {
            return RemoveResult.invalid();
        }
        Entry removed = data.entries.remove(storageKey(canonical.get().key()));
        if (removed == null) {
            return RemoveResult.missing(canonical.get().key());
        }
        save(server);
        return RemoveResult.removed(removed);
    }

    public static synchronized List<Entry> entries(MinecraftServer server) {
        ensureLoaded(server);
        return data.entries.values().stream()
                .sorted(Comparator.comparing(entry -> entry.addedAt == null ? "" : entry.addedAt))
                .map(entry -> entry.copy())
                .toList();
    }

    public static synchronized String exportCsv(MinecraftServer server) {
        ensureLoaded(server);
        List<String> lines = new ArrayList<>();
        lines.add("type,id,addedAt,addedByName,addedByUuid,originalInput");
        for (Entry entry : entries(server)) {
            lines.add(String.join(",",
                    csv(entry.type),
                    csv(entry.id),
                    csv(entry.addedAt),
                    csv(entry.addedByName),
                    csv(entry.addedByUuid),
                    csv(entry.originalInput)));
        }
        return String.join("\r\n", lines) + "\r\n";
    }

    public static Component denialMessage(ServerPlayer player, String sourceUrl, String actionText) {
        String display = canonicalId(sourceUrl).orElse(sourceUrl == null ? "" : sourceUrl);
        MutableComponent message = Component.literal("该音源未加入白名单，已拒绝" + actionText + "：")
                .withStyle(ChatFormatting.RED)
                .append(Component.literal(display).withStyle(ChatFormatting.YELLOW));

        CommandSourceStack source = player == null ? null : player.createCommandSourceStack();
        if (NetMusicBiliServerCommands.canManageWhitelist(source)) {
            String command = "/" + NetMusicBiliServerCommands.ROOT_COMMAND + " whitelist add "
                    + commandArgument(sourceUrl == null ? "" : sourceUrl);
            return message
                    .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("[点击添加白名单]")
                            .withStyle(style -> style
                                    .withColor(ChatFormatting.GREEN)
                                    .withUnderlined(true)
                                    .withClickEvent(new ClickEvent.RunCommand(command))
                                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击执行：")
                                            .withStyle(ChatFormatting.YELLOW)
                                            .append(Component.literal(command).withStyle(ChatFormatting.GRAY))))));
        }

        String contact = Config.linkWhitelistContactPlaceholder == null
                || Config.linkWhitelistContactPlaceholder.isBlank()
                        ? "管理员"
                        : Config.linkWhitelistContactPlaceholder.trim();
        return message.append(Component.literal("。请联系 " + contact + " 添加白名单。")
                .withStyle(ChatFormatting.GRAY));
    }

    private static void ensureLoaded(MinecraftServer server) {
        Path path = storagePath(server);
        if (path.equals(loadedPath)) {
            return;
        }

        loadedPath = path;
        data = new WhitelistData();
        if (!Files.isRegularFile(path)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            WhitelistData loaded = GSON.fromJson(reader, WhitelistData.class);
            if (loaded != null && loaded.entries != null) {
                data = loaded;
            }
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("读取链接白名单失败，将使用空白名单: {}", path, e);
            data = new WhitelistData();
        }
    }

    private static void save(MinecraftServer server) throws IOException {
        Path path = storagePath(server);
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        }
    }

    private static Path storagePath(MinecraftServer server) {
        return storageDir(server).resolve(DATA_FILE);
    }

    private static Path storageDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(NetMusicCanPlayBili.MODID);
    }

    private static String storageKey(String canonicalId) {
        if (canonicalId == null) {
            return "";
        }
        String value = canonicalId.trim();
        int split = value.indexOf(':');
        if (split < 0) {
            return value;
        }
        return value.substring(0, split).toLowerCase(java.util.Locale.ROOT) + value.substring(split);
    }

    private static String normalizeVideoSelection(BiliApiClient.VideoSelection selection) {
        if (selection == null) {
            return "";
        }
        return BiliApiClient.formatStoredVideoSelection(selection.videoId(), selection.page());
    }

    private static String normalizeUrl(String raw) {
        String value = raw.trim();
        if (value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isBlank()) {
                return value;
            }
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) || "ftp".equalsIgnoreCase(scheme)) {
                String host = uri.getHost();
                URI normalized = new URI(
                        scheme.toLowerCase(java.util.Locale.ROOT),
                        uri.getUserInfo(),
                        host == null ? null : host.toLowerCase(java.util.Locale.ROOT),
                        uri.getPort(),
                        uri.getPath(),
                        uri.getQuery(),
                        null);
                return normalized.toASCIIString();
            }
            return uri.normalize().toString();
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String commandArgument(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static final class WhitelistData {
        Map<String, Entry> entries = new LinkedHashMap<>();
    }

    public static final class Entry {
        public String type = "";
        public String id = "";
        public String originalInput = "";
        public String addedByName = "";
        public String addedByUuid = "";
        public String addedAt = "";

        private Entry copy() {
            Entry copy = new Entry();
            copy.type = type;
            copy.id = id;
            copy.originalInput = originalInput;
            copy.addedByName = addedByName;
            copy.addedByUuid = addedByUuid;
            copy.addedAt = addedAt;
            return copy;
        }
    }

    public record CanonicalResource(String type, String id) {
        public String key() {
            return type + ":" + id;
        }
    }

    public record AddResult(Status status, Entry entry) {
        public enum Status {
            ADDED, DUPLICATE, INVALID
        }

        static AddResult added(Entry entry) {
            return new AddResult(Status.ADDED, entry.copy());
        }

        static AddResult duplicate(Entry entry) {
            return new AddResult(Status.DUPLICATE, entry.copy());
        }

        static AddResult invalid() {
            return new AddResult(Status.INVALID, null);
        }
    }

    public record RemoveResult(Status status, String requestedId, Entry entry) {
        public enum Status {
            REMOVED, MISSING, INVALID
        }

        static RemoveResult removed(Entry entry) {
            return new RemoveResult(Status.REMOVED, entry.id, entry.copy());
        }

        static RemoveResult missing(String requestedId) {
            return new RemoveResult(Status.MISSING, requestedId, null);
        }

        static RemoveResult invalid() {
            return new RemoveResult(Status.INVALID, "", null);
        }
    }
}