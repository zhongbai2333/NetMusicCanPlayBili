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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    static final int MAX_AUDIT_TEXT_LENGTH = 256;
    private static final int MAX_REVIEW_RESOURCE_LENGTH = 512;
    private static final String LEGACY_REMOVAL_NOTE = "系统兼容调用";

    private static Path loadedPath;
    private static WhitelistData data = new WhitelistData();
    private static Exception loadFailure;

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
        // live: 前缀和 live.bilibili.com 链接归一化成同一条 live 记录。
        String liveRoomId = com.zhongbai233.net_music_can_play_bili.bili.BiliLiveRoomInput
                .parseExplicitRoomId(trimmed);
        if (!liveRoomId.isEmpty()) {
            return Optional.of(new CanonicalResource("live", liveRoomId));
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
        String canonicalId = canonical.get().key();
        if (canonicalId.length() > MAX_REVIEW_RESOURCE_LENGTH) {
            return AddResult.invalid();
        }

        String key = storageKey(canonicalId);
        Entry previous = data.entries.get(key);
        if (previous != null) {
            return AddResult.duplicate(previous);
        }

        Entry entry = new Entry();
        entry.id = canonicalId;
        entry.type = canonical.get().type();
        entry.originalInput = raw == null ? "" : raw.trim();
        entry.addedByName = player != null ? player.getDisplayName().getString() : "Console";
        UUID uuid = player != null ? player.getUUID() : null;
        entry.addedByUuid = uuid != null ? uuid.toString() : "";
        entry.addedAt = Instant.now().toString();
        WhitelistData next = data.copy();
        next.entries.put(key, entry);
        save(server, next);
        data = next;
        return AddResult.added(entry);
    }

    public static synchronized RemoveResult remove(MinecraftServer server, String raw) throws IOException {
        return remove(server, raw, null, LEGACY_REMOVAL_NOTE);
    }

    public static synchronized RemoveResult remove(MinecraftServer server, String raw, ServerPlayer player,
            String note) throws IOException {
        return remove(server, raw, player, note, null);
    }

    public static synchronized RemoveResult remove(MinecraftServer server, String raw, ServerPlayer player,
            String note, String expectedAddedAt) throws IOException {
        ensureLoaded(server);
        Optional<CanonicalResource> canonical = canonicalResource(raw);
        if (canonical.isEmpty()) {
            return RemoveResult.invalid();
        }
        String normalizedNote = normalizeAuditText(note);
        if (normalizedNote.isEmpty()) {
            return RemoveResult.noteRequired(canonical.get().key());
        }
        String key = storageKey(canonical.get().key());
        Entry removed = data.entries.get(key);
        if (removed == null) {
            return RemoveResult.missing(canonical.get().key());
        }
        if (expectedAddedAt != null && !safe(removed.addedAt).equals(expectedAddedAt)) {
            return RemoveResult.stale(canonical.get().key());
        }
        RemovalRecord record = RemovalRecord.create(removed, player, normalizedNote);
        WhitelistData next = data.copy();
        next.entries.remove(key);
        next.removalRecords.add(record);
        save(server, next);
        data = next;
        return RemoveResult.removed(removed, record);
    }

    public static synchronized CommentResult addComment(MinecraftServer server, String raw, ServerPlayer player,
            String text) throws IOException {
        return addComment(server, raw, player, text, null);
    }

    public static synchronized CommentResult addComment(MinecraftServer server, String raw, ServerPlayer player,
            String text, String expectedAddedAt) throws IOException {
        ensureLoaded(server);
        Optional<CanonicalResource> canonical = canonicalResource(raw);
        if (canonical.isEmpty()) {
            return CommentResult.invalid();
        }
        String normalizedText = normalizeAuditText(text);
        if (normalizedText.isEmpty()) {
            return CommentResult.commentRequired(canonical.get().key());
        }
        String key = storageKey(canonical.get().key());
        Entry existing = data.entries.get(key);
        if (existing == null) {
            return CommentResult.missing(canonical.get().key());
        }
        if (expectedAddedAt != null && !safe(existing.addedAt).equals(expectedAddedAt)) {
            return CommentResult.stale(canonical.get().key());
        }
        ReviewComment comment = ReviewComment.create(player, normalizedText);
        WhitelistData next = data.copy();
        Entry updated = next.entries.get(key);
        updated.comments.add(comment);
        save(server, next);
        data = next;
        return CommentResult.added(updated, comment);
    }

    public static synchronized List<Entry> entries(MinecraftServer server) {
        return entries(server, Integer.MAX_VALUE);
    }

    public static synchronized List<Entry> entries(MinecraftServer server, int limit) {
        ensureLoaded(server);
        return data.entries.values().stream()
                .sorted(Comparator.comparing(entry -> entry.addedAt == null ? "" : entry.addedAt))
                .limit(Math.max(0, limit))
                .map(entry -> entry.copy())
                .toList();
    }

    /** 审核界面的活动条目分页；最近添加的条目优先，避免新记录被旧记录遮住。 */
    public static synchronized List<Entry> entryPage(MinecraftServer server, int offset, int limit) {
        ensureLoaded(server);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(0, limit);
        Comparator<Entry> newestFirst = Comparator
                .comparing((Entry entry) -> safe(entry.addedAt))
                .thenComparing(entry -> safe(entry.id))
                .reversed();
        return data.entries.values().stream()
                .sorted(newestFirst)
                .skip(safeOffset)
                .limit(safeLimit)
                .map(entry -> entry.copy())
                .toList();
    }

    public static synchronized int entryCount(MinecraftServer server) {
        ensureLoaded(server);
        return data.entries.size();
    }

    public static synchronized List<RemovalRecord> removalRecords(MinecraftServer server) {
        return removalRecords(server, Integer.MAX_VALUE);
    }

    public static synchronized List<RemovalRecord> removalRecords(MinecraftServer server, int limit) {
        ensureLoaded(server);
        int count = Math.min(data.removalRecords.size(), Math.max(0, limit));
        List<RemovalRecord> records = new ArrayList<>(count);
        for (int index = data.removalRecords.size() - 1;
                index >= data.removalRecords.size() - count;
                index--) {
            records.add(data.removalRecords.get(index).copy());
        }
        return List.copyOf(records);
    }

    /** 审核界面的移除历史分页；最近移除的记录优先。 */
    public static synchronized List<RemovalRecord> removalRecordPage(MinecraftServer server, int offset, int limit) {
        ensureLoaded(server);
        int size = data.removalRecords.size();
        int safeOffset = Math.min(size, Math.max(0, offset));
        int safeLimit = Math.max(0, limit);
        int end = Math.min(size, safeOffset + safeLimit);
        List<RemovalRecord> records = new ArrayList<>(Math.max(0, end - safeOffset));
        for (int position = safeOffset; position < end; position++) {
            records.add(data.removalRecords.get(size - 1 - position).copy());
        }
        return List.copyOf(records);
    }

    public static synchronized int removalRecordCount(MinecraftServer server) {
        ensureLoaded(server);
        return data.removalRecords.size();
    }

    /** 在同一把锁内取得审核总数和两页数据，避免并发写入导致页码与内容不一致。 */
    public static synchronized ReviewSnapshot reviewSnapshot(MinecraftServer server,
            int requestedEntryOffset, int entryPageSize, int requestedRemovalOffset, int removalPageSize) {
        ensureLoaded(server);
        int totalEntries = data.entries.size();
        int totalRemovals = data.removalRecords.size();
        int entryOffset = clampPageOffset(requestedEntryOffset, totalEntries, entryPageSize);
        int removalOffset = clampPageOffset(requestedRemovalOffset, totalRemovals, removalPageSize);
        return new ReviewSnapshot(entryOffset, totalEntries,
                entryPage(server, entryOffset, entryPageSize),
                removalOffset, totalRemovals,
                removalRecordPage(server, removalOffset, removalPageSize));
    }

    private static int clampPageOffset(int requestedOffset, int total, int pageSize) {
        if (total <= 0 || pageSize <= 0) {
            return 0;
        }
        int maximum = ((total - 1) / pageSize) * pageSize;
        return Math.min(maximum, Math.max(0, requestedOffset));
    }

    public static synchronized String exportCsv(MinecraftServer server) {
        ensureLoaded(server);
        return exportCsv(data);
    }

    static String exportCsv(WhitelistData source) {
        List<String> lines = new ArrayList<>();
        lines.add("type,id,addedAt,addedByName,addedByUuid,originalInput,status,comments,"
                + "removedAt,removedByName,removedByUuid,removalNote,removalRecordId");
        if (source == null) {
            return String.join("\r\n", lines) + "\r\n";
        }
        for (Entry entry : source.entries.values()) {
            lines.add(csvRow(entry, "ACTIVE", "", "", "", "", ""));
        }
        for (RemovalRecord record : source.removalRecords) {
            lines.add(csvRow(record.entry, "REMOVED", record.removedAt, record.removedByName,
                    record.removedByUuid, record.note, record.recordId));
        }
        return String.join("\r\n", lines) + "\r\n";
    }

    private static String csvRow(Entry entry, String status, String removedAt, String removedByName,
            String removedByUuid, String removalNote, String removalRecordId) {
        Entry safeEntry = entry == null ? new Entry() : entry;
        return String.join(",",
                csv(safeEntry.type),
                csv(safeEntry.id),
                csv(safeEntry.addedAt),
                csv(safeEntry.addedByName),
                csv(safeEntry.addedByUuid),
                csv(safeEntry.originalInput),
                csv(status),
                csv(formatComments(safeEntry.comments)),
                csv(removedAt),
                csv(removedByName),
                csv(removedByUuid),
                csv(removalNote),
                csv(removalRecordId));
    }

    private static String formatComments(List<ReviewComment> comments) {
        if (comments == null || comments.isEmpty()) {
            return "";
        }
        return comments.stream()
                .map(comment -> spreadsheetSafe(safe(comment.createdAt)) + " | "
                        + spreadsheetSafe(safe(comment.authorName)) + " | "
                        + spreadsheetSafe(safe(comment.text)))
                .reduce((left, right) -> left + " || " + right)
                .orElse("");
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
        loadFailure = null;
        data = new WhitelistData();
        if (!Files.isRegularFile(path)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            WhitelistData loaded = GSON.fromJson(reader, WhitelistData.class);
            if (loaded == null) {
                throw new JsonParseException("白名单文件内容为空");
            }
            loaded.normalizeAfterLoad();
            data = loaded;
        } catch (IOException | JsonParseException e) {
            LOGGER.error("读取链接白名单失败；为避免覆盖原文件，本次运行将拒绝白名单写入: {}", path, e);
            loadFailure = e;
            data = new WhitelistData();
        }
    }

    private static void save(MinecraftServer server, WhitelistData next) throws IOException {
        Path path = storagePath(server);
        if (loadFailure != null && path.equals(loadedPath)) {
            throw new IOException("白名单文件读取失败，已拒绝覆盖原文件；请修复文件后重启服务器", loadFailure);
        }
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(next, writer);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            loadFailure = null;
        } catch (IOException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
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

    private static String normalizeAuditText(String value) {
        String normalized = safe(value).replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= MAX_AUDIT_TEXT_LENGTH
                ? normalized
                : normalized.substring(0, MAX_AUDIT_TEXT_LENGTH);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** 防止导出的 CSV 在 Excel/LibreOffice 中把用户可控单元格解释为公式。 */
    private static String spreadsheetSafe(String value) {
        String safeValue = safe(value);
        int firstVisible = 0;
        while (firstVisible < safeValue.length() && Character.isWhitespace(safeValue.charAt(firstVisible))) {
            firstVisible++;
        }
        if (firstVisible < safeValue.length()
                && "=+-@".indexOf(safeValue.charAt(firstVisible)) >= 0) {
            return "'" + safeValue;
        }
        return safeValue;
    }

    private static String csv(String value) {
        String safeValue = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
        safeValue = spreadsheetSafe(safeValue);
        return "\"" + safeValue.replace("\"", "\"\"") + "\"";
    }

    private static final class WhitelistData {
        int schemaVersion = 2;
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<RemovalRecord> removalRecords = new ArrayList<>();

        private WhitelistData copy() {
            WhitelistData copy = new WhitelistData();
            copy.schemaVersion = schemaVersion;
            copy.entries = new LinkedHashMap<>();
            entries.forEach((key, entry) -> copy.entries.put(key, entry.copy()));
            copy.removalRecords = removalRecords.stream().map(record -> record.copy())
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            return copy;
        }

        private void normalizeAfterLoad() {
            schemaVersion = 2;
            if (entries == null) {
                entries = new LinkedHashMap<>();
            }
            entries.values().removeIf(java.util.Objects::isNull);
            entries.values().forEach(entry -> entry.normalizeAfterLoad());
            if (removalRecords == null) {
                removalRecords = new ArrayList<>();
            }
            removalRecords.removeIf(java.util.Objects::isNull);
            removalRecords.forEach(record -> record.normalizeAfterLoad());
            removalRecords.sort(Comparator.comparing(
                    record -> safe(record.removedAt)));
        }
    }

    public static final class Entry {
        public String type = "";
        public String id = "";
        public String originalInput = "";
        public String addedByName = "";
        public String addedByUuid = "";
        public String addedAt = "";
        public List<ReviewComment> comments = new ArrayList<>();

        private void normalizeAfterLoad() {
            type = safe(type);
            id = safe(id);
            originalInput = safe(originalInput);
            addedByName = safe(addedByName);
            addedByUuid = safe(addedByUuid);
            addedAt = safe(addedAt);
            if (comments == null) {
                comments = new ArrayList<>();
            }
            comments.removeIf(java.util.Objects::isNull);
            comments.forEach(comment -> comment.normalizeAfterLoad());
        }

        private Entry copy() {
            Entry copy = new Entry();
            copy.type = type;
            copy.id = id;
            copy.originalInput = originalInput;
            copy.addedByName = addedByName;
            copy.addedByUuid = addedByUuid;
            copy.addedAt = addedAt;
            copy.comments = comments == null ? new ArrayList<>() : comments.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(comment -> comment.copy())
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            return copy;
        }
    }

    public static final class ReviewComment {
        public String text = "";
        public String authorName = "";
        public String authorUuid = "";
        public String createdAt = "";

        private static ReviewComment create(ServerPlayer player, String text) {
            ReviewComment comment = new ReviewComment();
            comment.text = text;
            comment.authorName = player != null ? player.getDisplayName().getString() : "Console";
            comment.authorUuid = player != null ? player.getUUID().toString() : "";
            comment.createdAt = Instant.now().toString();
            return comment;
        }

        private void normalizeAfterLoad() {
            text = safe(text);
            authorName = safe(authorName);
            authorUuid = safe(authorUuid);
            createdAt = safe(createdAt);
        }

        private ReviewComment copy() {
            ReviewComment copy = new ReviewComment();
            copy.text = text;
            copy.authorName = authorName;
            copy.authorUuid = authorUuid;
            copy.createdAt = createdAt;
            return copy;
        }
    }

    public static final class RemovalRecord {
        public String recordId = "";
        public Entry entry = new Entry();
        public String removedAt = "";
        public String removedByName = "";
        public String removedByUuid = "";
        public String note = "";

        private static RemovalRecord create(Entry entry, ServerPlayer player, String note) {
            RemovalRecord record = new RemovalRecord();
            record.recordId = UUID.randomUUID().toString();
            record.entry = entry.copy();
            record.removedAt = Instant.now().toString();
            record.removedByName = player != null ? player.getDisplayName().getString() : "Console";
            record.removedByUuid = player != null ? player.getUUID().toString() : "";
            record.note = note;
            return record;
        }

        private void normalizeAfterLoad() {
            recordId = safe(recordId);
            if (entry == null) {
                entry = new Entry();
            }
            entry.normalizeAfterLoad();
            removedAt = safe(removedAt);
            removedByName = safe(removedByName);
            removedByUuid = safe(removedByUuid);
            note = safe(note);
            if (recordId.isBlank()) {
                recordId = stableLegacyRecordId();
            }
        }

        private String stableLegacyRecordId() {
            String fingerprint = String.join("\n",
                    safe(entry.id),
                    removedAt,
                    removedByName,
                    removedByUuid,
                    note);
            return UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8)).toString();
        }

        private RemovalRecord copy() {
            RemovalRecord copy = new RemovalRecord();
            copy.recordId = recordId;
            copy.entry = entry == null ? new Entry() : entry.copy();
            copy.removedAt = removedAt;
            copy.removedByName = removedByName;
            copy.removedByUuid = removedByUuid;
            copy.note = note;
            return copy;
        }
    }

    public record ReviewSnapshot(int entryOffset, int totalEntries, List<Entry> entries,
            int removalOffset, int totalRemovalRecords, List<RemovalRecord> removalRecords) {
    }

    public record CanonicalResource(String type, String id) {
        public String key() {
            return type + ":" + id;
        }
    }

    public record AddResult(Status status, Entry entry) {
        public enum Status { ADDED, DUPLICATE, INVALID }
        static AddResult added(Entry entry) { return new AddResult(Status.ADDED, entry.copy()); }
        static AddResult duplicate(Entry entry) { return new AddResult(Status.DUPLICATE, entry.copy()); }
        static AddResult invalid() { return new AddResult(Status.INVALID, null); }
    }

    public record RemoveResult(Status status, String requestedId, Entry entry, RemovalRecord removalRecord) {
        public enum Status { REMOVED, MISSING, INVALID, NOTE_REQUIRED, STALE }
        static RemoveResult removed(Entry entry, RemovalRecord record) {
            return new RemoveResult(Status.REMOVED, entry.id, entry.copy(), record.copy());
        }
        static RemoveResult missing(String id) { return new RemoveResult(Status.MISSING, id, null, null); }
        static RemoveResult invalid() { return new RemoveResult(Status.INVALID, "", null, null); }
        static RemoveResult noteRequired(String id) { return new RemoveResult(Status.NOTE_REQUIRED, id, null, null); }
        static RemoveResult stale(String id) { return new RemoveResult(Status.STALE, id, null, null); }
    }

    public record CommentResult(Status status, String requestedId, Entry entry, ReviewComment comment) {
        public enum Status { ADDED, MISSING, INVALID, COMMENT_REQUIRED, STALE }
        static CommentResult added(Entry entry, ReviewComment comment) {
            return new CommentResult(Status.ADDED, entry.id, entry.copy(), comment.copy());
        }
        static CommentResult missing(String id) { return new CommentResult(Status.MISSING, id, null, null); }
        static CommentResult invalid() { return new CommentResult(Status.INVALID, "", null, null); }
        static CommentResult commentRequired(String id) {
            return new CommentResult(Status.COMMENT_REQUIRED, id, null, null);
        }
        static CommentResult stale(String id) { return new CommentResult(Status.STALE, id, null, null); }
    }
}
