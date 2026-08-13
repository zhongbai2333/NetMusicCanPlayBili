package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleSnapshotBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ControlConsoleConfigPacket(BlockPos pos, UUID leaseId, UUID operationId, long expectedRevision,
    String displayName,
    double hardRangeX, double hardRangeY, double hardRangeZ,
    List<ControlConsoleElement> elements) implements CustomPacketPayload {
    public static final Type<ControlConsoleConfigPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("control_console_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ControlConsoleConfigPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ControlConsoleConfigPacket decode(RegistryFriendlyByteBuf buf) {
                    int startIndex = buf.readerIndex();
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    UUID leaseId = buf.readUUID();
                    UUID operationId = buf.readUUID();
                    long revision = buf.readLong();
                    String name = buf.readUtf(64);
                    double rangeX = buf.readDouble();
                    double rangeY = buf.readDouble();
                    double rangeZ = buf.readDouble();
                    int count = buf.readVarInt();
                    if (count < 0 || count > ControlConsoleDocument.MAX_ELEMENTS) {
                        throw new IllegalArgumentException("invalid control console element count: " + count);
                    }
                    List<ControlConsoleElement> elements = new ArrayList<>(count);
                    java.util.Set<UUID> ids = new java.util.HashSet<>();
                    for (int i = 0; i < count; i++) {
                        ControlConsoleElement element = readElement(buf);
                        if (!ids.add(element.elementId())) throw new IllegalArgumentException("duplicate elementId");
                        elements.add(element);
                        if (buf.readerIndex() - startIndex > ControlConsoleSnapshotBudget.MAX_BYTES) {
                            throw new IllegalArgumentException("control console snapshot exceeds 64 KiB");
                        }
                    }
                        return new ControlConsoleConfigPacket(pos, leaseId, operationId, revision,
                            name, rangeX, rangeY, rangeZ,
                            List.copyOf(elements));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ControlConsoleConfigPacket packet) {
                    BlockPos.STREAM_CODEC.encode(buf, packet.pos());
                    buf.writeUUID(packet.leaseId());
                    buf.writeUUID(packet.operationId());
                    buf.writeLong(packet.expectedRevision());
                    buf.writeUtf(packet.displayName(), 64);
                    buf.writeDouble(packet.hardRangeX());
                    buf.writeDouble(packet.hardRangeY());
                    buf.writeDouble(packet.hardRangeZ());
                    List<ControlConsoleElement> elements = List.copyOf(packet.elements());
                    if (elements.size() > ControlConsoleDocument.MAX_ELEMENTS) {
                        throw new IllegalArgumentException("too many control console elements");
                    }
                    ControlConsoleSnapshotBudget.requireWithinLimit(packet.displayName(), elements);
                    buf.writeVarInt(elements.size());
                    java.util.Set<UUID> ids = new java.util.HashSet<>();
                    for (ControlConsoleElement element : elements) {
                        if (!ids.add(element.elementId())) throw new IllegalArgumentException("duplicate elementId");
                        writeElement(buf, element);
                    }
                }
            };

    public ControlConsoleConfigPacket {
        pos = pos.immutable();
        leaseId = Objects.requireNonNull(leaseId, "leaseId");
        operationId = Objects.requireNonNull(operationId, "operationId");
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        elements = List.copyOf(elements);
        ControlConsoleSnapshotBudget.requireWithinLimit(displayName, elements);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ControlConsoleConfigPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)
            || !player.mayBuild()
                || !NetworkRateLimiter.allow(player.getUUID(), "control_console_config", 4)
                || player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0D
                || !level.hasChunk(Math.floorDiv(payload.pos().getX(), 16),
                    Math.floorDiv(payload.pos().getZ(), 16))
                || !(level.getBlockEntity(payload.pos()) instanceof ControlConsoleBlockEntity console)) {
            if (context.player() instanceof ServerPlayer rejected) {
            PacketDistributor.sendToPlayer(rejected, new ControlConsoleConfigResultPacket(payload.pos(),
                payload.operationId(), -1L, ControlConsoleConfigResultPacket.Status.REJECTED));
            }
            return;
        }
        console.claimIfUnowned(player.getUUID());
        if (!console.canEdit(player)
            || !com.zhongbai233.net_music_can_play_bili.server.ControlConsoleEditLeaseRegistry.validate(
                ControlConsoleEditLeasePacket.key(level, payload.pos()), player.getUUID(), payload.leaseId(),
                System.currentTimeMillis())) {
            PacketDistributor.sendToPlayer(player, new ControlConsoleConfigResultPacket(payload.pos(),
                    payload.operationId(), console.documentRevision(),
                    ControlConsoleConfigResultPacket.Status.REJECTED));
            return;
        }
        try {
            ControlConsoleBlockEntity.ReplaceResult result = console.replaceDocument(payload.operationId(),
                payload.expectedRevision(), payload.displayName(), payload.hardRangeX(), payload.hardRangeY(),
                payload.hardRangeZ(), payload.elements());
            sendResult(player, payload, console, result);
        } catch (IllegalArgumentException ignored) {
            PacketDistributor.sendToPlayer(player, new ControlConsoleConfigResultPacket(payload.pos(),
                payload.operationId(), console.documentRevision(),
                ControlConsoleConfigResultPacket.Status.REJECTED));
        }
    }

    private static void sendResult(ServerPlayer player, ControlConsoleConfigPacket payload,
            ControlConsoleBlockEntity console, ControlConsoleBlockEntity.ReplaceResult result) {
        ControlConsoleConfigResultPacket.Status status = ControlConsoleConfigResultPacket.fromReplaceResult(result);
        PacketDistributor.sendToPlayer(player, new ControlConsoleConfigResultPacket(payload.pos(),
                payload.operationId(), console.documentRevision(), status,
                status == ControlConsoleConfigResultPacket.Status.CONFLICT ? console.document() : null));
    }

    static ControlConsoleElement readElement(RegistryFriendlyByteBuf buf) {
        return new ControlConsoleElement(buf.readUUID(), ControlConsoleElement.Type.parse(buf.readUtf(16)),
                buf.readUtf(ControlConsoleElement.MAX_NAME_LENGTH),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readUtf(32), buf.readUtf(4096),
            buf.readBoolean(), buf.readBoolean(), buf.readFloat(), buf.readInt(), buf.readFloat(),
            buf.readInt(), buf.readFloat(), buf.readBoolean(), buf.readInt(), buf.readInt(),
            ControlConsoleElement.Alignment.values()[buf.readUnsignedByte()], buf.readFloat(), buf.readBoolean(),
            buf.readBoolean(), buf.readBoolean(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readFloat());
    }

    static void writeElement(RegistryFriendlyByteBuf buf, ControlConsoleElement element) {
        buf.writeUUID(element.elementId());
        buf.writeUtf(element.type().name(), 16);
        buf.writeUtf(element.name(), ControlConsoleElement.MAX_NAME_LENGTH);
        buf.writeFloat(element.distance());
        buf.writeFloat(element.offsetX());
        buf.writeFloat(element.offsetY());
        buf.writeFloat(element.height());
        buf.writeFloat(element.aspect());
        buf.writeFloat(element.yaw());
        buf.writeFloat(element.pitch());
        buf.writeFloat(element.roll());
        buf.writeUtf(element.contentMode(), 32);
        buf.writeUtf(element.text(), 4096);
        buf.writeBoolean(element.followLyrics());
        buf.writeBoolean(element.showTranslation());
        buf.writeFloat(element.textScale());
        buf.writeInt(element.color());
        buf.writeFloat(element.volume());
        buf.writeInt(element.channelIndex());
        buf.writeFloat(element.maxDistance());
        buf.writeBoolean(element.autoMixJoc());
        buf.writeInt(element.translationColor());
        buf.writeInt(element.backgroundColor());
        buf.writeByte(element.alignment().ordinal());
        buf.writeFloat(element.maxWidth());
        buf.writeBoolean(element.wrap());
        buf.writeBoolean(element.enabled());
        buf.writeBoolean(element.locked());
        buf.writeFloat(element.scaleX());
        buf.writeFloat(element.scaleY());
        buf.writeFloat(element.scaleZ());
        buf.writeFloat(element.pivotX());
        buf.writeFloat(element.pivotY());
        buf.writeFloat(element.pivotZ());
        buf.writeFloat(element.skewXByY());
        buf.writeFloat(element.skewYByX());
    }
}
