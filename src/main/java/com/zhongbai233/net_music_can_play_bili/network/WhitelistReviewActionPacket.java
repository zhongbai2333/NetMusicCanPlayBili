package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.IOException;

/** 白名单审核界面的服务端操作。 */
public record WhitelistReviewActionPacket(Action action, String idOrLink, long targetMillis)
        implements CustomPacketPayload {
    private static final int MAX_VALUE_LENGTH = 512;
    public static final Type<WhitelistReviewActionPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("whitelist_review_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistReviewActionPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public WhitelistReviewActionPacket decode(RegistryFriendlyByteBuf buffer) {
            return new WhitelistReviewActionPacket(Action.byId(buffer.readVarInt()), buffer.readUtf(MAX_VALUE_LENGTH),
                    buffer.readVarLong());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, WhitelistReviewActionPacket packet) {
            buffer.writeVarInt(packet.action().ordinal());
            buffer.writeUtf(packet.idOrLink() == null ? "" : packet.idOrLink(), MAX_VALUE_LENGTH);
            buffer.writeVarLong(Math.max(0L, packet.targetMillis()));
        }
    };

    public WhitelistReviewActionPacket(Action action, String idOrLink) {
        this(action, idOrLink, 0L);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WhitelistReviewActionPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!NetworkRateLimiter.allow(player.getUUID(), "whitelist_review_action", 3)) {
            return;
        }
        if (!WhitelistReviewPacket.canOpen(player)) {
            WhitelistReviewPacket.rejectUnauthorized(player);
            return;
        }
        switch (payload.action()) {
            case REFRESH -> WhitelistReviewPacket.sendTo(player);
            case EXPORT -> {
                String csv = BiliWhitelistManager.exportCsv(player.level().getServer());
                PacketDistributor.sendToPlayer(player, WhitelistCsvExportPacket.create(csv));
                player.sendSystemMessage(Component.literal("已导出当前白名单 CSV。")
                        .withStyle(ChatFormatting.GREEN));
            }
            case REMOVE -> removeEntry(player, payload.idOrLink());
            case PREVIEW -> WhitelistPreviewPacket.resolveAndSend(player, payload.idOrLink(), 0L, true);
            case PREVIEW_SEEK -> WhitelistPreviewPacket.resolveAndSend(player, payload.idOrLink(),
                    Math.max(0L, payload.targetMillis()), true);
        }
    }

    private static void removeEntry(ServerPlayer player, String idOrLink) {
        try {
            BiliWhitelistManager.RemoveResult result = BiliWhitelistManager.remove(player.level().getServer(),
                    idOrLink);
            switch (result.status()) {
                case REMOVED -> player.sendSystemMessage(Component.literal("已从白名单移除：" + result.entry().id)
                        .withStyle(ChatFormatting.GREEN));
                case MISSING -> player.sendSystemMessage(Component.literal("白名单中没有：" + result.requestedId())
                        .withStyle(ChatFormatting.YELLOW));
                case INVALID -> player.sendSystemMessage(Component.literal("无法识别要移除的白名单条目。")
                        .withStyle(ChatFormatting.RED));
            }
        } catch (IOException e) {
            player.sendSystemMessage(Component.literal("保存白名单失败：" + e.getMessage())
                    .withStyle(ChatFormatting.RED));
        }
        WhitelistReviewPacket.sendTo(player);
    }

    public enum Action {
        REFRESH,
        REMOVE,
        EXPORT,
        PREVIEW,
        PREVIEW_SEEK;

        public static Action byId(int id) {
            Action[] values = values();
            return id >= 0 && id < values.length ? values[id] : REFRESH;
        }
    }
}
