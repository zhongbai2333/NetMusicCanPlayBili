package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;
import java.util.UUID;

public record MP4QueueSelectionPacket(int containerSlotIndex, int selectedQueueIndex) implements CustomPacketPayload {
    public static final Type<MP4QueueSelectionPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("mp4_queue_selection"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4QueueSelectionPacket> STREAM_CODEC = StreamCodec
            .composite(
                    ByteBufCodecs.INT, packet -> packet.containerSlotIndex(),
                    ByteBufCodecs.INT, packet -> packet.selectedQueueIndex(),
                    (containerSlotIndex, selectedQueueIndex) -> new MP4QueueSelectionPacket(
                            containerSlotIndex == null ? -1 : containerSlotIndex.intValue(),
                            selectedQueueIndex == null ? 0 : selectedQueueIndex.intValue()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MP4QueueSelectionPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (payload.containerSlotIndex() < 0 || payload.containerSlotIndex() >= player.containerMenu.slots.size()) {
            return;
        }
        Slot slot = player.containerMenu.slots.get(payload.containerSlotIndex());
        ItemStack stack = Objects.requireNonNull(slot.getItem(), "slot item");
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        UUID deviceId = MP4DeviceIdentity.ensureUniqueForContainerSlot(level, player, stack);
        if (deviceId == null) {
            return;
        }
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        MP4Item.State state = entry.state();
        int queueSize = MP4Item.queueSize(stack);
        if (queueSize <= 0) {
            queueSize = entry.queue().size();
        }
        int maxIndex = Math.max(0, queueSize - 1);
        int selected = Math.max(0, Math.min(maxIndex, payload.selectedQueueIndex()));
        int progressPerMille = selected == state.selectedQueueIndex() ? state.progressPerMille() : 0;
        MP4DeviceStateStore.updateState(level, deviceId, new MP4Item.State(state.playing(), state.shuffle(),
                state.videoEnabled(), state.landscape(), state.qualityIndex(), selected, state.queueScrollOffset(),
                state.volumePerMille(), state.repeatMode(), state.playlistOpen(), state.lyricsEnabled(),
                state.subtitleMode(), state.subtitleAiEnabled(), progressPerMille, state.rotationHintShown()));
        MP4DeviceStateStore.syncQueueCopy(level, deviceId, stack);
        MP4DeviceStateStore.DeviceEntry updated = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        PacketDistributor.sendToPlayer(player, new MP4ContainerDeviceIdPacket(payload.containerSlotIndex(), deviceId));
        PacketDistributor.sendToPlayer(player, MP4DeviceStateMirrorPacket.fromEntry(deviceId, updated,
            com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex.hasHeadphoneLinkedToMp4(deviceId)));
        slot.setChanged();
        player.containerMenu.broadcastChanges();
    }
}
