package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 服务端权威 MP4 聚焦界面状态响应。 */
public record MP4OpenStatePacket(InteractionHand hand, UUID deviceId, MP4StatePacket state, long updatedGameTime,
    boolean headphoneLinked, List<ItemStack> queue) implements CustomPacketPayload {
    public static final Type<MP4OpenStatePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, "mp4_open_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4OpenStatePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MP4OpenStatePacket decode(RegistryFriendlyByteBuf buffer) {
            InteractionHand hand = buffer.readBoolean() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            UUID deviceId = buffer.readUUID();
            MP4StatePacket state = MP4StatePacket.STREAM_CODEC.decode(buffer);
            long updatedGameTime = buffer.readLong();
            boolean headphoneLinked = buffer.readBoolean();
            int size = Math.max(0, Math.min(MP4Item.MAX_QUEUE_SIZE, buffer.readVarInt()));
            List<ItemStack> queue = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                queue.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
            }
            return new MP4OpenStatePacket(hand, deviceId, state, updatedGameTime, headphoneLinked,
                List.copyOf(queue));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, MP4OpenStatePacket packet) {
            buffer.writeBoolean(packet.hand() == InteractionHand.OFF_HAND);
            buffer.writeUUID(packet.deviceId());
            MP4StatePacket.STREAM_CODEC.encode(buffer, packet.state());
            buffer.writeLong(packet.updatedGameTime());
            buffer.writeBoolean(packet.headphoneLinked());
            List<ItemStack> queue = packet.queue() == null ? List.of() : packet.queue();
            int size = Math.min(MP4Item.MAX_QUEUE_SIZE, queue.size());
            buffer.writeVarInt(size);
            for (int i = 0; i < size; i++) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, queue.get(i));
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static MP4OpenStatePacket fromEntry(InteractionHand hand, UUID deviceId,
            MP4DeviceStateStore.DeviceEntry entry) {
        return new MP4OpenStatePacket(hand, deviceId, MP4StatePacket.fromState(entry.state(), deviceId),
            entry.updatedGameTime(), false, entry.queue());
    }

    public static MP4OpenStatePacket fromStack(ServerLevel level, InteractionHand hand, UUID deviceId,
            ItemStack stack) {
        if (level != null && deviceId != null && stack.getItem() instanceof MP4Item) {
            MP4DeviceStateStore.syncQueueCopy(level, deviceId, stack);
        }
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        List<ItemStack> queue = stack.getItem() instanceof MP4Item ? MP4Item.readQueue(stack) : entry.queue();
        if (queue.isEmpty()) {
            queue = entry.queue();
        }
        boolean headphoneLinked = AudioLinkIndex.hasHeadphoneLinkedToMp4(deviceId);
        return new MP4OpenStatePacket(hand, deviceId, MP4StatePacket.fromState(entry.state(), deviceId),
            entry.updatedGameTime(), headphoneLinked, queue);
    }

    public static void handle(MP4OpenStatePacket payload, IPayloadContext context) {
        context.enqueueWork(() -> MP4Client.receiveOpenState(payload.hand(), payload.deviceId(),
            payload.state().toState(), payload.updatedGameTime(), payload.headphoneLinked(), payload.queue()));
    }
}
