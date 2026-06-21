package com.zhongbai233.net_music_can_play_bili.menu;

import com.zhongbai233.net_music_can_play_bili.init.ModMenus;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class MediaToolReportMenu extends AbstractContainerMenu {
    public static final int MAX_SOURCES = 12;
    private final List<ReportSourceInfo> sources;

    public MediaToolReportMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, List.of());
    }

    public MediaToolReportMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, readSources(buffer));
    }

    public MediaToolReportMenu(int containerId, Inventory inventory, List<ReportSourceInfo> sources) {
        super(ModMenus.MEDIA_TOOL_REPORT.get(), containerId);
        this.sources = List.copyOf(sources != null ? sources : List.of());
    }

    public List<ReportSourceInfo> sources() {
        return sources;
    }

    public boolean containsSourceKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return sources.stream().anyMatch(source -> key.equals(source.key()));
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    public static ReportSourceInfo fromActiveSource(MinecraftServer server, ServerPlayer viewer,
            PlaybackAuditManager.ActiveSource source) {
        String ownerName = Component.translatable("gui.net_music_can_play_bili.media_tool_report.unknown_owner")
                .getString();
        if (server != null && source.ownerId() != null) {
            ServerPlayer owner = server.getPlayerList().getPlayer(source.ownerId());
            ownerName = owner != null ? owner.getDisplayName().getString() : source.ownerId().toString();
        }
        double distance = viewer != null && viewer.level().dimension().equals(source.levelKey())
                ? Math.sqrt(source.sourcePos().distToCenterSqr(viewer.position()))
                : -1.0D;
        return new ReportSourceInfo(source.key(), source.kind().shortName(), source.kind().displayName(),
                source.levelKey().identifier().toString(), source.sourcePos(), source.songName(), source.rawUrl(),
                source.elapsedMillis(), source.durationSeconds(), ownerName, distance);
    }

    public static void writeClientData(RegistryFriendlyByteBuf buffer, List<ReportSourceInfo> sources) {
        List<ReportSourceInfo> safeSources = sources != null ? sources : List.of();
        int count = Math.min(MAX_SOURCES, safeSources.size());
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            safeSources.get(i).write(buffer);
        }
    }

    private static List<ReportSourceInfo> readSources(RegistryFriendlyByteBuf buffer) {
        if (buffer == null || !buffer.isReadable()) {
            return List.of();
        }
        int count = Math.min(MAX_SOURCES, Math.max(0, buffer.readVarInt()));
        List<ReportSourceInfo> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(ReportSourceInfo.read(buffer));
        }
        return result;
    }

    public record ReportSourceInfo(String key, String kindShortName, String kindDisplayName, String dimension,
            BlockPos pos, String songName, String rawUrl, long elapsedMillis, int durationSeconds, String ownerName,
            double distance) {
        private static final int MAX_TEXT_LENGTH = 512;

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(safe(key), MAX_TEXT_LENGTH);
            buffer.writeUtf(safe(kindShortName), MAX_TEXT_LENGTH);
            buffer.writeUtf(safe(kindDisplayName), MAX_TEXT_LENGTH);
            buffer.writeUtf(safe(dimension), MAX_TEXT_LENGTH);
            buffer.writeBlockPos(pos != null ? pos : BlockPos.ZERO);
            buffer.writeUtf(safe(songName), MAX_TEXT_LENGTH);
            buffer.writeUtf(safe(rawUrl), MAX_TEXT_LENGTH);
            buffer.writeVarLong(Math.max(0L, elapsedMillis));
            buffer.writeVarInt(Math.max(0, durationSeconds));
            buffer.writeUtf(safe(ownerName), MAX_TEXT_LENGTH);
            buffer.writeDouble(distance);
        }

        private static ReportSourceInfo read(RegistryFriendlyByteBuf buffer) {
            return new ReportSourceInfo(buffer.readUtf(MAX_TEXT_LENGTH), buffer.readUtf(MAX_TEXT_LENGTH),
                    buffer.readUtf(MAX_TEXT_LENGTH), buffer.readUtf(MAX_TEXT_LENGTH), buffer.readBlockPos(),
                    buffer.readUtf(MAX_TEXT_LENGTH), buffer.readUtf(MAX_TEXT_LENGTH), buffer.readVarLong(),
                    buffer.readVarInt(), buffer.readUtf(MAX_TEXT_LENGTH), buffer.readDouble());
        }

        public String shortSongName() {
            return trim(songName, 28);
        }

        public String shortOwnerName() {
            return trim(ownerName, 18);
        }

        public String positionText() {
            return pos.getX() + " " + pos.getY() + " " + pos.getZ();
        }

        public String progressText() {
            return formatMillis(elapsedMillis) + "/" + formatMillis(durationSeconds * 1000L);
        }

        public String distanceText() {
            return distance >= 0.0D ? String.format(java.util.Locale.ROOT, "%.1fm", distance) : "?m";
        }

        private static String safe(String value) {
            return value != null ? value : "";
        }

        private static String trim(String value, int maxLength) {
            String safeValue = safe(value).isBlank() ? "?" : value;
            return safeValue.length() <= maxLength ? safeValue
                    : safeValue.substring(0, Math.max(1, maxLength - 1)) + "…";
        }

        private static String formatMillis(long millis) {
            long totalSeconds = Math.max(0L, millis) / 1000L;
            return (totalSeconds / 60L) + ":" + String.format(java.util.Locale.ROOT, "%02d", totalSeconds % 60L);
        }
    }
}