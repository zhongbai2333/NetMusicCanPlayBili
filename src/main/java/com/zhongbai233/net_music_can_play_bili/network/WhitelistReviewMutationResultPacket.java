package com.zhongbai233.net_music_can_play_bili.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 服务端对白名单审核操作的明确回执，供界面结束等待、清稿、重试或返回列表。 */
public record WhitelistReviewMutationResultPacket(Mutation mutation, Status status,
        String targetId, String message, long requestId) implements CustomPacketPayload {
    private static final int MAX_TARGET_LENGTH = 512;
    private static final int MAX_MESSAGE_LENGTH = 256;
    public static final Type<WhitelistReviewMutationResultPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("whitelist_review_mutation_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistReviewMutationResultPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public WhitelistReviewMutationResultPacket decode(RegistryFriendlyByteBuf buffer) {
                    return new WhitelistReviewMutationResultPacket(
                            Mutation.byId(buffer.readVarInt()),
                            Status.byId(buffer.readVarInt()),
                            buffer.readUtf(MAX_TARGET_LENGTH),
                            buffer.readUtf(MAX_MESSAGE_LENGTH),
                            readRequestId(buffer));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, WhitelistReviewMutationResultPacket packet) {
                    buffer.writeVarInt(packet.mutation().ordinal());
                    buffer.writeVarInt(packet.status().ordinal());
                    buffer.writeUtf(bounded(packet.targetId(), MAX_TARGET_LENGTH), MAX_TARGET_LENGTH);
                    buffer.writeUtf(bounded(packet.message(), MAX_MESSAGE_LENGTH), MAX_MESSAGE_LENGTH);
                    buffer.writeVarLong(Math.max(0L, packet.requestId()));
                }
            };

    public WhitelistReviewMutationResultPacket(Mutation mutation, Status status, String targetId, String message) {
        this(mutation, status, targetId, message, 0L);
    }

    public boolean successful() {
        return status == Status.SUCCESS;
    }

    public static void sendTo(ServerPlayer player, Mutation mutation, Status status,
            String targetId, String message, long requestId) {
        if (player != null) {
            PacketDistributor.sendToPlayer(player,
                    new WhitelistReviewMutationResultPacket(mutation, status, targetId, message, requestId));
        }
    }

    private static String bounded(String value, int maximum) {
        String safe = value == null ? "" : value;
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    private static long readRequestId(RegistryFriendlyByteBuf buffer) {
        long value = buffer.readVarLong();
        if (value < 0L) {
            throw new DecoderException("Invalid whitelist mutation request id: " + value);
        }
        return value;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WhitelistReviewMutationResultPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> com.zhongbai233.net_music_can_play_bili.gui.WhitelistReviewScreen
                .routeMutationResult(payload));
    }

    public enum Mutation {
        REMOVE,
        COMMENT,
        REFRESH,
        PREVIEW,
        PREVIEW_SEEK;

        private static Mutation byId(int id) {
            Mutation[] values = values();
            if (id < 0 || id >= values.length) {
                throw new DecoderException("Invalid whitelist mutation id: " + id);
            }
            return values[id];
        }
    }

    public enum Status {
        SUCCESS,
        RATE_LIMITED,
        DENIED,
        MISSING,
        INVALID,
        REQUIRED,
        STALE,
        SAVE_FAILED;

        private static Status byId(int id) {
            Status[] values = values();
            if (id < 0 || id >= values.length) {
                throw new DecoderException("Invalid whitelist mutation result id: " + id);
            }
            return values[id];
        }
    }
}
