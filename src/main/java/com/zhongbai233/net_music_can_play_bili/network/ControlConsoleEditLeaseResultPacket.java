package com.zhongbai233.net_music_can_play_bili.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;
import java.util.UUID;

/** 服务端编辑租约请求结果。 */
public record ControlConsoleEditLeaseResultPacket(BlockPos pos, Status status, UUID leaseId)
        implements CustomPacketPayload {
    public static final Type<ControlConsoleEditLeaseResultPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("control_console_edit_lease_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControlConsoleEditLeaseResultPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ControlConsoleEditLeaseResultPacket decode(RegistryFriendlyByteBuf buf) {
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    Status status = Status.fromId(buf.readVarInt());
                    UUID leaseId = buf.readBoolean() ? buf.readUUID() : null;
                    return new ControlConsoleEditLeaseResultPacket(pos, status, leaseId);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ControlConsoleEditLeaseResultPacket packet) {
                    BlockPos.STREAM_CODEC.encode(buf, packet.pos());
                    buf.writeVarInt(packet.status().id);
                    buf.writeBoolean(packet.leaseId() != null);
                    if (packet.leaseId() != null) {
                        buf.writeUUID(packet.leaseId());
                    }
                }
            };

    public ControlConsoleEditLeaseResultPacket {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        status = Objects.requireNonNull(status, "status");
        if (status == Status.GRANTED && leaseId == null) {
            throw new IllegalArgumentException("granted lease requires leaseId");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ControlConsoleEditLeaseResultPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient
                .acceptLeaseResult(payload));
    }

    public enum Status {
        GRANTED(0), BUSY(1), REJECTED(2), EXPIRED(3);

        private final int id;

        Status(int id) {
            this.id = id;
        }

        private static Status fromId(int id) {
            for (Status status : values()) {
                if (status.id == id) {
                    return status;
                }
            }
            throw new IllegalArgumentException("unknown edit lease result status: " + id);
        }
    }
}