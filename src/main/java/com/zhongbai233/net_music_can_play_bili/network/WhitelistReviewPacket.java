package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.NetMusicBiliServerCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** 客户端白名单审核界面的列表快照。 */
public record WhitelistReviewPacket(List<Entry> entries) implements CustomPacketPayload {
    public static final Type<WhitelistReviewPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("whitelist_review"));
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_FIELD_LENGTH = 512;

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistReviewPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public WhitelistReviewPacket decode(RegistryFriendlyByteBuf buffer) {
            int count = Math.max(0, Math.min(MAX_ENTRIES, buffer.readVarInt()));
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(new Entry(
                        buffer.readUtf(32),
                        buffer.readUtf(MAX_FIELD_LENGTH),
                        buffer.readUtf(128),
                        buffer.readUtf(128),
                        buffer.readUtf(64),
                        buffer.readUtf(MAX_FIELD_LENGTH)));
            }
            return new WhitelistReviewPacket(List.copyOf(entries));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, WhitelistReviewPacket packet) {
            List<Entry> safeEntries = packet.entries() == null ? List.of() : packet.entries();
            int count = Math.min(MAX_ENTRIES, safeEntries.size());
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                Entry entry = safeEntries.get(i);
                buffer.writeUtf(safe(entry.type()), 32);
                buffer.writeUtf(safe(entry.id()), MAX_FIELD_LENGTH);
                buffer.writeUtf(safe(entry.addedAt()), 128);
                buffer.writeUtf(safe(entry.addedByName()), 128);
                buffer.writeUtf(safe(entry.addedByUuid()), 64);
                buffer.writeUtf(safe(entry.originalInput()), MAX_FIELD_LENGTH);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static WhitelistReviewPacket create(List<BiliWhitelistManager.Entry> entries) {
        List<Entry> result = new ArrayList<>();
        if (entries != null) {
            for (BiliWhitelistManager.Entry entry : entries) {
                result.add(new Entry(entry.type, entry.id, entry.addedAt, entry.addedByName,
                        entry.addedByUuid, entry.originalInput));
                if (result.size() >= MAX_ENTRIES) {
                    break;
                }
            }
        }
        return new WhitelistReviewPacket(List.copyOf(result));
    }

    public static void sendTo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, create(BiliWhitelistManager.entries(player.level().getServer())));
    }

    public static boolean canOpen(ServerPlayer player) {
        return player != null && NetMusicBiliServerCommands.canManageWhitelist(player.createCommandSourceStack());
    }

    public static void handle(WhitelistReviewPacket payload, IPayloadContext context) {
        context.enqueueWork(
                () -> com.zhongbai233.net_music_can_play_bili.gui.WhitelistReviewScreen.openOrUpdate(payload));
    }

    public static void rejectUnauthorized(ServerPlayer player) {
        if (player != null) {
            player.sendSystemMessage(Component.literal("需要 OP4 权限才能审核白名单。")
                    .withStyle(ChatFormatting.RED));
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record Entry(String type, String id, String addedAt, String addedByName, String addedByUuid,
            String originalInput) {
    }
}
