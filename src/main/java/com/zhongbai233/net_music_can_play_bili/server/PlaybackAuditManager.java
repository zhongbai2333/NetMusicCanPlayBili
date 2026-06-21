package com.zhongbai233.net_music_can_play_bili.server;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 现代化唱片机和 MP4 设备的服务端播放审计状态与源粒子。 */
public final class PlaybackAuditManager {
    private static final int STALE_AFTER_TICKS = 80;
    private static final int PARTICLE_INTERVAL_TICKS = 8;
    private static final long REPORT_NOTIFY_COOLDOWN_TICKS = 20L * 45L;
    private static final int REPORT_OP_REMINDER_LIMIT = 5;
    private static final Map<String, ActiveSource> SOURCES = new ConcurrentHashMap<>();
    private static final Map<String, ReportState> REPORTS = new ConcurrentHashMap<>();

    private PlaybackAuditManager() {
    }

    public static void recordModernTurntable(ServerLevel level, BlockPos pos, String songName, String rawUrl,
            int durationSeconds, long elapsedMillis, UUID ownerId) {
        record("turntable:" + level.dimension() + ":" + pos.asLong(), SourceKind.MODERN_TURNTABLE,
                level, pos, pos.getX() + 0.5D, pos.getY() + 1.15D, pos.getZ() + 0.5D,
                songName, rawUrl, durationSeconds, elapsedMillis, ownerId);
    }

    public static void recordMp4(ServerLevel level, UUID deviceId, BlockPos sourcePos, String songName, String rawUrl,
            int durationSeconds, long elapsedMillis, UUID ownerId) {
        if (sourcePos == null) {
            return;
        }
        recordMp4(level, deviceId, sourcePos, sourcePos.getX() + 0.5D, sourcePos.getY() + 1.15D,
                sourcePos.getZ() + 0.5D, songName, rawUrl, durationSeconds, elapsedMillis, ownerId);
    }

    public static void recordMp4(ServerLevel level, UUID deviceId, BlockPos sourcePos, double particleX,
            double particleY, double particleZ, String songName, String rawUrl, int durationSeconds,
            long elapsedMillis, UUID ownerId) {
        if (deviceId == null) {
            return;
        }
        record("mp4:" + deviceId, SourceKind.MP4, level, sourcePos, particleX, particleY, particleZ,
                songName, rawUrl, durationSeconds, elapsedMillis, ownerId);
    }

    private static void record(String key, SourceKind kind, ServerLevel level, BlockPos sourcePos, double particleX,
            double particleY, double particleZ, String songName, String rawUrl, int durationSeconds,
            long elapsedMillis, UUID ownerId) {
        if (level == null || sourcePos == null) {
            return;
        }
        long gameTime = level.getGameTime();
        SOURCES.put(key, new ActiveSource(key, kind, level.dimension(), sourcePos.immutable(),
                safe(songName, "未知歌曲"), safe(rawUrl, ""), Math.max(0, durationSeconds),
                Math.max(0L, elapsedMillis), ownerId, gameTime));
        spawnSourceNoteParticle(level, particleX, particleY, particleZ, gameTime);
    }

    public static List<ActiveSource> snapshot(MinecraftServer server) {
        if (server == null) {
            return List.of();
        }
        prune(server);
        List<ActiveSource> result = new ArrayList<>(SOURCES.values());
        result.sort(Comparator
                .comparing((ActiveSource source) -> source.kind().displayName())
                .thenComparing(source -> source.levelKey().toString())
                .thenComparingInt(source -> source.sourcePos().getX())
                .thenComparingInt(source -> source.sourcePos().getY())
                .thenComparingInt(source -> source.sourcePos().getZ()));
        return result;
    }

    public static ActiveSource findModernTurntable(MinecraftServer server, ServerLevel level, BlockPos pos) {
        if (server == null || level == null || pos == null) {
            return null;
        }
        prune(server);
        return SOURCES.get("turntable:" + level.dimension() + ":" + pos.asLong());
    }

    public static ActiveSource findMp4(MinecraftServer server, UUID deviceId) {
        if (server == null || deviceId == null) {
            return null;
        }
        prune(server);
        return SOURCES.get("mp4:" + deviceId);
    }

    public static ActiveSource findByKey(MinecraftServer server, String key) {
        if (server == null || key == null || key.isBlank()) {
            return null;
        }
        prune(server);
        return SOURCES.get(key);
    }

    public static ReportResult notifyOpsOfReport(ServerPlayer reporter, ActiveSource source, String reason) {
        if (reporter == null || source == null) {
            return ReportResult.EMPTY;
        }
        MinecraftServer server = reporter.level().getServer();
        if (server == null) {
            return ReportResult.EMPTY;
        }
        long now = reporter.level().getGameTime();
        ReportState state = REPORTS.compute(source.key(), (key, existing) -> {
            ReportState safe = existing != null ? existing : new ReportState(now);
            safe.record(reporter, now);
            return safe;
        });
        boolean firstReport = state.totalReports() == 1;
        boolean reachedOpLimit = state.totalReports() >= REPORT_OP_REMINDER_LIMIT;
        boolean shouldNotifyOps = firstReport
                || (!state.opReminderLimitReached()
                        && now - state.lastNotifiedGameTime() >= REPORT_NOTIFY_COOLDOWN_TICKS)
                || (reachedOpLimit && !state.opReminderLimitReached());
        if (!shouldNotifyOps) {
            return new ReportResult(false, true, state.totalReports(), state.uniqueReporterCount(), false,
                    state.opReminderLimitReached());
        }

        MutableComponent message = reportMessage(server, source, reporter.getDisplayName().copy(), reason, state,
                true);
        if (reachedOpLimit && !state.opReminderLimitReached()) {
            message.append(Component.literal("\n  ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("该音源举报已达到 OP 提醒上限，后续同源举报将静默合并")
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        }
        int notified = broadcastToOnlineOps(server, message);
        server.sendSystemMessage(message);
        state.markNotified(now, reachedOpLimit);
        return new ReportResult(notified > 0, !firstReport, state.totalReports(), state.uniqueReporterCount(),
                reachedOpLimit, state.opReminderLimitReached());
    }

    private static MutableComponent reportMessage(MinecraftServer server, ActiveSource source, Component reporterName,
            String reason, ReportState state, boolean includeSuppressed) {
        MutableComponent message = Component.literal("[音源举报] ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .append(reporterName.copy().withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" 举报 ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(source.kind().displayName()).withStyle(source.kind().color(),
                        ChatFormatting.BOLD))
                .append(Component.literal("：").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(reason == null || reason.isBlank() ? "未填写原因" : reason)
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("累计 " + state.totalReports() + " 次 / "
                        + state.uniqueReporterCount() + " 人").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("\n  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(source.describe(server));
        int suppressed = Math.max(0, state.totalReports() - state.notifiedReportCount() - 1);
        if (includeSuppressed && suppressed > 0) {
            message.append(Component.literal("\n  ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("已合并未单独提醒的同源举报：" + suppressed + " 次")
                            .withStyle(ChatFormatting.YELLOW));
        }
        if (isLikelyBiliSource(source.rawUrl())) {
            message.append(Component.literal("\n  ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(openBiliComponent(source.rawUrl()));
        }
        return message;
    }

    private static int broadcastToOnlineOps(MinecraftServer server, Component message) {
        int notified = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isOpLevelTwoOrHigher(server, player)) {
                player.sendSystemMessage(message);
                notified++;
            }
        }
        return notified;
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null || !isOpLevelTwoOrHigher(server, player)) {
            return;
        }
        prune(server);
        List<PendingReport> pendingReports = REPORTS.entrySet().stream()
                .map(entry -> new PendingReport(SOURCES.get(entry.getKey()), entry.getValue()))
                .filter(report -> report.source() != null && report.state().totalReports() > 0)
                .sorted(Comparator.comparingLong(report -> report.state().lastReportGameTime()))
                .toList();
        if (pendingReports.isEmpty()) {
            return;
        }
        player.sendSystemMessage(Component.literal("[音源举报] ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .append(Component.literal("当前有 " + pendingReports.size() + " 个活跃举报音源")
                        .withStyle(ChatFormatting.GOLD))
                .append(Component.literal("（上线补发）").withStyle(ChatFormatting.GRAY)));
        for (PendingReport report : pendingReports) {
            player.sendSystemMessage(reportMessage(server, report.source(), Component.literal("离线期间玩家"),
                    "上线补发聚合举报", report.state(), false));
        }
    }

    private static boolean isLikelyBiliSource(String rawUrl) {
        String value = rawUrl != null ? rawUrl.toLowerCase(java.util.Locale.ROOT) : "";
        return value.contains("bilibili.com") || value.contains("b23.tv") || value.contains("bv");
    }

    private static Component openBiliComponent(String rawUrl) {
        String url = normalizedBiliUrl(rawUrl);
        URI uri = safeUri(url);
        if (uri == null) {
            String source = rawUrl != null ? rawUrl : "";
            return Component.literal("[复制 B站来源]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withHoverEvent(new HoverEvent.ShowText(
                                    Component.literal("来源不是完整 URL，请手动复制：\n").withStyle(ChatFormatting.YELLOW)
                                            .append(Component.literal(source).withStyle(ChatFormatting.GRAY)))));
        }
        return Component.literal("[打开/复制 B站来源]")
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(uri))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("点击打开来源；如无法打开请复制：\n").withStyle(ChatFormatting.YELLOW)
                                        .append(Component.literal(url).withStyle(ChatFormatting.GRAY)))));
    }

    private static URI safeUri(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? uri : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalizedBiliUrl(String rawUrl) {
        String value = rawUrl != null ? rawUrl.trim() : "";
        if (value.isBlank()) {
            return "";
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try {
                URI uri = URI.create(value);
                return uri.toString();
            } catch (IllegalArgumentException ignored) {
                return "";
            }
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(BV[0-9A-Za-z]+)").matcher(value);
        if (!matcher.find()) {
            return "";
        }
        String bvid = matcher.group(1);
        String page = "";
        java.util.regex.Matcher pageMatcher = java.util.regex.Pattern.compile("(?:^|[|&?])p=(\\d+)").matcher(value);
        if (pageMatcher.find()) {
            page = "?p=" + pageMatcher.group(1);
        }
        return "https://www.bilibili.com/video/" + bvid + page;
    }

    private static boolean isOpLevelTwoOrHigher(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) {
            return false;
        }
        try {
            NameAndId profile = new NameAndId(player.getGameProfile());
            if (server.isSingleplayerOwner(profile)) {
                return true;
            }
            PermissionLevel level = server.getProfilePermissions(profile).level();
            return level == PermissionLevel.GAMEMASTERS
                    || level == PermissionLevel.ADMINS
                    || level == PermissionLevel.OWNERS;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer() != null && event.getServer().getTickCount() % STALE_AFTER_TICKS == 0) {
            prune(event.getServer());
        }
    }

    private static void prune(MinecraftServer server) {
        SOURCES.values().removeIf(source -> {
            ServerLevel level = server.getLevel(source.levelKey());
            return level == null || level.getGameTime() - source.lastSeenGameTime() > STALE_AFTER_TICKS;
        });
        REPORTS.keySet().removeIf(key -> !SOURCES.containsKey(key));
    }

    private static void spawnSourceNoteParticle(ServerLevel level, double x, double y, double z, long gameTime) {
        if (gameTime % PARTICLE_INTERVAL_TICKS != 0L) {
            return;
        }
        level.sendParticles(ParticleTypes.NOTE,
                x, y, z,
                2, 0.35D, 0.15D, 0.35D, 0.0D);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public enum SourceKind {
        MODERN_TURNTABLE("现代化唱片机", "唱片机", ChatFormatting.LIGHT_PURPLE),
        MP4("MP4", "MP4", ChatFormatting.GREEN);

        private final String displayName;
        private final String shortName;
        private final ChatFormatting color;

        SourceKind(String displayName, String shortName, ChatFormatting color) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.color = color;
        }

        public String displayName() {
            return displayName;
        }

        public String shortName() {
            return shortName;
        }

        public ChatFormatting color() {
            return color;
        }
    }

    public record ActiveSource(String key, SourceKind kind, ResourceKey<Level> levelKey, BlockPos sourcePos,
            String songName, String rawUrl, int durationSeconds, long elapsedMillis, UUID ownerId,
            long lastSeenGameTime) {
        public Component describe(MinecraftServer server) {
            String ownerName = "未知玩家";
            if (ownerId != null) {
                ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
                ownerName = player != null ? player.getDisplayName().getString() : ownerId.toString();
            }
            MutableComponent line = Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(kind.shortName()).withStyle(kind.color(), ChatFormatting.BOLD))
                    .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(trimmed(songName, 32).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(formatMillis(elapsedMillis) + "/" + formatMillis(durationSeconds * 1000L))
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("  @").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(trimPlain(ownerName, 18)).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(teleportComponent());
            if (!rawUrl.isBlank()) {
                line = line.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("来源: ").withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(rawUrl).withStyle(ChatFormatting.YELLOW)))));
            }
            return line;
        }

        private Component teleportComponent() {
            String dimension = levelKey.identifier().toString();
            String posText = sourcePos.getX() + " " + sourcePos.getY() + " " + sourcePos.getZ();
            String command = "/execute in " + dimension + " run tp @s "
                    + (sourcePos.getX() + 0.5D) + " " + (sourcePos.getY() + 1.0D) + " " + (sourcePos.getZ() + 0.5D);
            return Component.literal("[" + posText + "]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.GOLD)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.RunCommand(command))
                            .withHoverEvent(new HoverEvent.ShowText(
                                    Component.literal("点击传送到音源\n").withStyle(ChatFormatting.YELLOW)
                                            .append(Component.literal(dimension + " " + posText)
                                                    .withStyle(ChatFormatting.GRAY)))));
        }

        private static MutableComponent trimmed(String value, int maxLength) {
            return Component.literal(trimPlain(value, maxLength));
        }

        private static String trimPlain(String value, int maxLength) {
            String safeValue = safe(value, "?");
            if (safeValue.length() <= maxLength) {
                return safeValue;
            }
            return safeValue.substring(0, Math.max(1, maxLength - 1)) + "…";
        }
    }

    private static String formatMillis(long millis) {
        long safeMillis = Math.max(0L, millis);
        long totalSeconds = safeMillis / 1000L;
        return (totalSeconds / 60L) + ":" + String.format("%02d", totalSeconds % 60L);
    }

    public record ReportResult(boolean notifiedOps, boolean merged, int totalReports, int uniqueReporters,
            boolean opReminderLimitReachedNow, boolean opReminderLimitReached) {
        private static final ReportResult EMPTY = new ReportResult(false, false, 0, 0, false, false);
    }

    private record PendingReport(ActiveSource source, ReportState state) {
    }

    private static final class ReportState {
        private final java.util.Set<UUID> reporterIds = ConcurrentHashMap.newKeySet();
        private int totalReports;
        private int notifiedReportCount;
        private long lastNotifiedGameTime = Long.MIN_VALUE;
        private long lastReportGameTime;
        private boolean opReminderLimitReached;

        private ReportState(long createdGameTime) {
        }

        private void record(ServerPlayer reporter, long now) {
            totalReports++;
            lastReportGameTime = now;
            reporterIds.add(reporter.getUUID());
        }

        private void markNotified(long now, boolean limitReached) {
            lastNotifiedGameTime = now;
            notifiedReportCount = totalReports;
            opReminderLimitReached |= limitReached;
        }

        private int totalReports() {
            return totalReports;
        }

        private int uniqueReporterCount() {
            return reporterIds.size();
        }

        private int notifiedReportCount() {
            return notifiedReportCount;
        }

        private long lastNotifiedGameTime() {
            return lastNotifiedGameTime;
        }

        private long lastReportGameTime() {
            return lastReportGameTime;
        }

        private boolean opReminderLimitReached() {
            return opReminderLimitReached;
        }
    }
}
