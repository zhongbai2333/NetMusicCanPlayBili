package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.UUID;

/** Creates a locked Pad copy that shares the draft Pad's device id. */
public record PadPublishPacket(UUID deviceId) implements CustomPacketPayload {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Type<PadPublishPacket> TYPE = new Type<>(NetworkPayloadIds.id("pad_publish"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PadPublishPacket> STREAM_CODEC = StreamCodec
            .of((buffer, packet) -> buffer.writeUUID(packet.deviceId()),
                    buffer -> new PadPublishPacket(buffer.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PadPublishPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || payload.deviceId() == null) {
            return;
        }
        if (!NetworkRateLimiter.allow(player.getUUID(), "pad_publish", 2)) {
            LOGGER.debug("丢弃过频 Pad 发布包: player={} device={}", player.getUUID(), payload.deviceId());
            return;
        }
        ItemStack draft = PadItem.findAllByDeviceId(player, payload.deviceId()).stream()
                .filter(PadItem::isPad)
                .filter(stack -> !PadItem.readLocked(stack))
                .findFirst()
                .orElse(ItemStack.EMPTY);
        if (!PadItem.isPad(draft)) {
            return;
        }
        PadDocument draftDocument = PadItem.readLegacyDocument(draft);
        draftDocument = (draftDocument == null ? PadDocument.DEFAULT : draftDocument).copyWithLocked(false);
        if (player.level() instanceof net.minecraft.server.level.ServerLevel level) {
            draftDocument = PadDocumentStore.getOrCreate(level, payload.deviceId(), draft).copyWithLocked(false);
            PadDocumentStore.update(level, payload.deviceId(), draftDocument);
        }
        PadItem.writeDeviceId(draft, payload.deviceId());
        PadItem.writeDocument(draft, draftDocument.copyWithLocked(false));
        PadItem.writeDocumentToLockedCopies(player, payload.deviceId(), draftDocument);

        ItemStack published = draft.copyWithCount(1);
        PadItem.writeDeviceId(published, payload.deviceId());
        PadItem.writeDocument(published, draftDocument.copyWithLocked(true));
        if (!player.getInventory().add(published)) {
            player.drop(published, false);
        }
        PadDeviceHolderTracker.invalidate(payload.deviceId());
        PacketDistributor.sendToPlayer(player, new PadStateMirrorPacket(payload.deviceId(), draftDocument,
                player.level().getGameTime()));
    }
}