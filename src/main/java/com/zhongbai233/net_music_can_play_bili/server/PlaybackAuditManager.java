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
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 现代化唱片机和 MP4 设备的服务端播放审计状态与源粒子。 */
public final class PlaybackAuditManager {
    private static final int STALE_AFTER_TICKS = 80;
    private static final int PARTICLE_INTERVAL_TICKS = 8;
    private static final Map<String, ActiveSource> SOURCES = new ConcurrentHashMap<>();

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
}
