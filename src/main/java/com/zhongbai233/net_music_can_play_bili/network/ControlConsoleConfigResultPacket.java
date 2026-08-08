package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity.ReplaceResult;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** 服务端对一次中控台自动保存请求的明确结果。 */
public record ControlConsoleConfigResultPacket(BlockPos pos, UUID operationId, long revision, Status status)
        implements CustomPacketPayload {
    public static final Type<ControlConsoleConfigResultPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("control_console_config_result"));
    private static final StreamCodec<RegistryFriendlyByteBuf, UUID> UUID_CODEC = new StreamCodec<>() {
        @Override
        public UUID decode(RegistryFriendlyByteBuf buffer) {
            return buffer.readUUID();
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, UUID value) {
            buffer.writeUUID(value);
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, ControlConsoleConfigResultPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, packet -> packet.pos(),
                    UUID_CODEC, packet -> packet.operationId(),
                    ByteBufCodecs.LONG, packet -> packet.revision(),
                    ByteBufCodecs.VAR_INT.map(id -> Status.fromId(id.intValue()), status -> status.id()),
                    packet -> packet.status(),
                    (pos, operationId, revision, status) ->
                        new ControlConsoleConfigResultPacket(pos, operationId, revision.longValue(), status));

    public enum Status {
        APPLIED(0), DUPLICATE(1), CONFLICT(2), REJECTED(3), READ_ONLY(4);

        private final int id;

        Status(int id) {
            this.id = id;
        }

        int id() {
            return id;
        }

        static Status fromId(int id) {
            for (Status status : values()) {
                if (status.id == id) {
                    return status;
                }
            }
            throw new IllegalArgumentException("unknown control console result status: " + id);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ControlConsoleConfigResultPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen
                .acceptControlConsoleConfigResult(payload));
    }

    public static Status fromReplaceResult(ReplaceResult result) {
        return switch (result) {
            case APPLIED -> Status.APPLIED;
            case DUPLICATE -> Status.DUPLICATE;
            case CONFLICT -> Status.CONFLICT;
            case READ_ONLY -> Status.READ_ONLY;
            case REJECTED -> Status.REJECTED;
        };
    }
}