package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity.ReplaceResult;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleConflictAuthority;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;
import java.util.Objects;

/** 服务端对一次中控台自动保存请求的明确结果。 */
public record ControlConsoleConfigResultPacket(BlockPos pos, UUID operationId, long revision, Status status,
        ControlConsoleDocument authoritativeDocument)
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
            new StreamCodec<>() {
                @Override
                public ControlConsoleConfigResultPacket decode(RegistryFriendlyByteBuf buf) {
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    UUID operationId = UUID_CODEC.decode(buf);
                    long revision = buf.readLong();
                    Status status = Status.fromId(buf.readVarInt());
                    ControlConsoleDocument authoritative = buf.readBoolean()
                            ? ControlConsoleDocumentPacketCodec.decode(buf) : null;
                    return new ControlConsoleConfigResultPacket(pos, operationId, revision, status, authoritative);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ControlConsoleConfigResultPacket packet) {
                    BlockPos.STREAM_CODEC.encode(buf, packet.pos());
                    UUID_CODEC.encode(buf, packet.operationId());
                    buf.writeLong(packet.revision());
                    buf.writeVarInt(packet.status().id());
                    buf.writeBoolean(packet.authoritativeDocument() != null);
                    if (packet.authoritativeDocument() != null) {
                        ControlConsoleDocumentPacketCodec.encode(buf, packet.authoritativeDocument());
                    }
                }
            };

    public ControlConsoleConfigResultPacket(BlockPos pos, UUID operationId, long revision, Status status) {
        this(pos, operationId, revision, status, null);
    }

    public ControlConsoleConfigResultPacket {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        operationId = Objects.requireNonNull(operationId, "operationId");
        status = Objects.requireNonNull(status, "status");
        if (revision < -1L) {
            throw new IllegalArgumentException("revision must be -1 or non-negative");
        }
        ControlConsoleConflictAuthority.validate(status == Status.CONFLICT, revision, authoritativeDocument);
    }

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
