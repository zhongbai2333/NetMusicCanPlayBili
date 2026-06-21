package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.menu.MediaToolReportMenu;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MediaToolReportPacket(String sourceKey) implements CustomPacketPayload {
    private static final int MAX_KEY_LENGTH = 256;

    public static final Type<MediaToolReportPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("media_tool_report"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MediaToolReportPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MediaToolReportPacket decode(RegistryFriendlyByteBuf buffer) {
            return new MediaToolReportPacket(buffer.readUtf(MAX_KEY_LENGTH));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, MediaToolReportPacket packet) {
            buffer.writeUtf(packet.sourceKey() != null ? packet.sourceKey() : "", MAX_KEY_LENGTH);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MediaToolReportPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof MediaToolReportMenu menu)
                || !menu.containsSourceKey(payload.sourceKey())) {
            return;
        }
        PlaybackAuditManager.ActiveSource source = PlaybackAuditManager.findByKey(
                player.level().getServer(), payload.sourceKey());
        if (source == null) {
            player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.media_tool.no_active_source").withStyle(ChatFormatting.RED));
            return;
        }
        PlaybackAuditManager.ReportResult result = PlaybackAuditManager.notifyOpsOfReport(player, source,
                "玩家使用媒体管理工具举报");
        String messageKey = result.notifiedOps()
                ? "message.net_music_can_play_bili.media_tool.report_sent"
                : result.merged()
                        ? "message.net_music_can_play_bili.media_tool.report_merged"
                        : "message.net_music_can_play_bili.media_tool.report_logged";
        player.sendSystemMessage(Component.translatable(messageKey, result.totalReports(), result.uniqueReporters())
                .withStyle(ChatFormatting.GOLD));
        player.closeContainer();
    }
}