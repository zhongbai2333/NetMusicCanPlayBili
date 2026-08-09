package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.editor.core.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.core.media.ControlConsoleRangeGate;
import com.zhongbai233.net_music_can_play_bili.server.ControlConsoleConsumerLeaseRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;
import java.util.UUID;

/** 玩家申请、续期或释放中控台媒体消费资格。 */
public record ControlConsoleConsumerLeasePacket(BlockPos pos, Action action, UUID leaseId, long consumerGeneration)
        implements CustomPacketPayload {
    public static final Type<ControlConsoleConsumerLeasePacket> TYPE = new Type<>(
            NetworkPayloadIds.id("control_console_consumer_lease"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControlConsoleConsumerLeasePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ControlConsoleConsumerLeasePacket decode(RegistryFriendlyByteBuf buf) {
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    Action action = Action.fromId(buf.readVarInt());
                    UUID leaseId = buf.readBoolean() ? buf.readUUID() : null;
                    return new ControlConsoleConsumerLeasePacket(pos, action, leaseId, buf.readVarLong());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ControlConsoleConsumerLeasePacket packet) {
                    BlockPos.STREAM_CODEC.encode(buf, packet.pos());
                    buf.writeVarInt(packet.action().id);
                    buf.writeBoolean(packet.leaseId() != null);
                    if (packet.leaseId() != null) {
                        buf.writeUUID(packet.leaseId());
                    }
                    buf.writeVarLong(packet.consumerGeneration());
                }
            };

    public ControlConsoleConsumerLeasePacket {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        action = Objects.requireNonNull(action, "action");
        if (action == Action.RELEASE && leaseId == null) {
            throw new IllegalArgumentException("release requires leaseId");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ControlConsoleConsumerLeasePacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        ControlConsoleConsumerLeaseRegistry.Key key = key(level, payload.pos(), player.getUUID());
        if (payload.action() == Action.RELEASE) {
            ControlConsoleConsumerLeaseRegistry.release(key, payload.leaseId());
            return;
        }
        if (!NetworkRateLimiter.allow(player.getUUID(), "control_console_consumer_lease_global", 64)
            || !NetworkRateLimiter.allow(player.getUUID(),
                "control_console_consumer_lease:" + payload.pos().asLong(), 4)
                || !level.hasChunk(Math.floorDiv(payload.pos().getX(), 16),
                    Math.floorDiv(payload.pos().getZ(), 16))
                || !(level.getBlockEntity(payload.pos()) instanceof ControlConsoleBlockEntity console)) {
                send(player, payload.pos(), ControlConsoleConsumerLeaseResultPacket.Status.REJECTED, null,
                    payload.consumerGeneration());
            return;
        }
        long now = System.currentTimeMillis();
        boolean existing = ControlConsoleConsumerLeaseRegistry.hasActive(key, now);
        var document = console.document();
        if (!hasValidLoadedSource(level, document)) {
            if (payload.leaseId() != null) {
                ControlConsoleConsumerLeaseRegistry.release(key, payload.leaseId());
            }
                send(player, payload.pos(), ControlConsoleConsumerLeaseResultPacket.Status.REJECTED, null,
                    payload.consumerGeneration());
            return;
        }
        var range = ControlConsoleRangeGate.evaluate(existing,
                player.getX() - (payload.pos().getX() + 0.5D),
                player.getY() - (payload.pos().getY() + 0.5D),
                player.getZ() - (payload.pos().getZ() + 0.5D),
                document.hardRangeX(), document.hardRangeY(), document.hardRangeZ());
        if (!range.active()) {
            if (payload.leaseId() != null) {
                ControlConsoleConsumerLeaseRegistry.release(key, payload.leaseId());
            }
                send(player, payload.pos(), ControlConsoleConsumerLeaseResultPacket.Status.OUTSIDE, null,
                    payload.consumerGeneration());
            return;
        }
        UUID leaseId;
        if (existing) {
            if (payload.leaseId() == null) {
                // 客户端越界后会忘记 leaseId；同一玩家重入时可安全取回并续期自己的现有租约。
                leaseId = ControlConsoleConsumerLeaseRegistry.acquireOrRenew(key, now);
            } else if (!ControlConsoleConsumerLeaseRegistry.renew(key, payload.leaseId(), now)) {
                send(player, payload.pos(), ControlConsoleConsumerLeaseResultPacket.Status.REJECTED, null,
                    payload.consumerGeneration());
                return;
            } else {
                leaseId = payload.leaseId();
            }
        } else {
            leaseId = ControlConsoleConsumerLeaseRegistry.acquireOrRenew(key, now);
        }
        send(player, payload.pos(), ControlConsoleConsumerLeaseResultPacket.Status.GRANTED, leaseId,
            payload.consumerGeneration());
    }

    public static ControlConsoleConsumerLeaseRegistry.Key key(ServerLevel level, BlockPos pos, UUID playerId) {
        return new ControlConsoleConsumerLeaseRegistry.Key(level.dimension().identifier().toString(),
                pos.asLong(), playerId);
    }

    private static boolean hasValidLoadedSource(ServerLevel level, ControlConsoleDocument document) {
        if (!document.hasSourceBinding()
                || !level.dimension().identifier().toString().equals(document.sourceDimension())) {
            return false;
        }
        BlockPos sourcePos = new BlockPos(document.sourceX(), document.sourceY(), document.sourceZ());
        if (!level.hasChunk(Math.floorDiv(sourcePos.getX(), 16), Math.floorDiv(sourcePos.getZ(), 16))) {
            return false;
        }
        var source = level.getBlockEntity(sourcePos);
        return switch (document.sourceKind()) {
            case TURNTABLE -> source instanceof ModernTurntableBlockEntity;
            case LIVE_STREAMER -> source instanceof LiveStreamerBlockEntity;
        };
    }

    private static void send(ServerPlayer player, BlockPos pos,
            ControlConsoleConsumerLeaseResultPacket.Status status, UUID leaseId, long consumerGeneration) {
        PacketDistributor.sendToPlayer(player,
                new ControlConsoleConsumerLeaseResultPacket(pos, status, leaseId, consumerGeneration));
    }

    public enum Action {
        ACQUIRE_OR_RENEW(0), RELEASE(1);

        private final int id;

        Action(int id) {
            this.id = id;
        }

        private static Action fromId(int id) {
            for (Action action : values()) {
                if (action.id == id) {
                    return action;
                }
            }
            throw new IllegalArgumentException("unknown consumer lease action: " + id);
        }
    }
}