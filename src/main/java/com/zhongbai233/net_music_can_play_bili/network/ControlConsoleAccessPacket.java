package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.editor.core.document.ControlConsoleDocument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 权限配置与普通场景快照分离，避免公共编辑者借场景保存提升自身权限。 */
public record ControlConsoleAccessPacket(BlockPos pos, UUID leaseId, UUID operationId, long expectedRevision,
        ControlConsoleDocument.AccessMode accessMode, Set<UUID> trustedPlayerIds) implements CustomPacketPayload {
    public static final Type<ControlConsoleAccessPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("control_console_access"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControlConsoleAccessPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ControlConsoleAccessPacket decode(RegistryFriendlyByteBuf buf) {
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    UUID leaseId = buf.readUUID();
                    UUID operationId = buf.readUUID();
                    long revision = buf.readLong();
                    ControlConsoleDocument.AccessMode mode = ControlConsoleDocument.AccessMode.parse(buf.readUtf(16));
                    int count = buf.readVarInt();
                    if (count < 0 || count > ControlConsoleDocument.MAX_TRUSTED_PLAYERS) {
                        throw new IllegalArgumentException("invalid trusted player count: " + count);
                    }
                    Set<UUID> trusted = new LinkedHashSet<>();
                    for (int i = 0; i < count; i++) {
                        trusted.add(buf.readUUID());
                    }
                    return new ControlConsoleAccessPacket(pos, leaseId, operationId, revision, mode, trusted);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ControlConsoleAccessPacket packet) {
                    BlockPos.STREAM_CODEC.encode(buf, packet.pos());
                    buf.writeUUID(packet.leaseId());
                    buf.writeUUID(packet.operationId());
                    buf.writeLong(packet.expectedRevision());
                    buf.writeUtf(packet.accessMode().name(), 16);
                    buf.writeVarInt(packet.trustedPlayerIds().size());
                    packet.trustedPlayerIds().forEach(buf::writeUUID);
                }
            };

    public ControlConsoleAccessPacket {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        leaseId = Objects.requireNonNull(leaseId, "leaseId");
        operationId = Objects.requireNonNull(operationId, "operationId");
        accessMode = Objects.requireNonNull(accessMode, "accessMode");
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        trustedPlayerIds = Set.copyOf(Objects.requireNonNull(trustedPlayerIds, "trustedPlayerIds"));
        if (trustedPlayerIds.size() > ControlConsoleDocument.MAX_TRUSTED_PLAYERS) {
            throw new IllegalArgumentException("too many trusted players");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ControlConsoleAccessPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)
                || !NetworkRateLimiter.allow(player.getUUID(), "control_console_access", 2)
                || player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0D
                || !level.hasChunk(Math.floorDiv(payload.pos().getX(), 16),
                    Math.floorDiv(payload.pos().getZ(), 16))
                || !(level.getBlockEntity(payload.pos()) instanceof ControlConsoleBlockEntity console)) {
            reject(payload, context, -1L);
            return;
        }
        if (!com.zhongbai233.net_music_can_play_bili.server.ControlConsoleEditLeaseRegistry.validate(
                ControlConsoleEditLeasePacket.key(level, payload.pos()), player.getUUID(), payload.leaseId(),
                System.currentTimeMillis())) {
            reject(payload, context, console.documentRevision());
            return;
        }
        ControlConsoleBlockEntity.ReplaceResult result = console.replaceAccessControl(player,
                payload.operationId(), payload.expectedRevision(), payload.accessMode(), payload.trustedPlayerIds());
        PacketDistributor.sendToPlayer(player, new ControlConsoleConfigResultPacket(payload.pos(),
                payload.operationId(), console.documentRevision(),
                ControlConsoleConfigResultPacket.fromReplaceResult(result)));
    }

    private static void reject(ControlConsoleAccessPacket payload, IPayloadContext context, long revision) {
        if (context.player() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, new ControlConsoleConfigResultPacket(payload.pos(),
                    payload.operationId(), revision, ControlConsoleConfigResultPacket.Status.REJECTED));
        }
    }
}