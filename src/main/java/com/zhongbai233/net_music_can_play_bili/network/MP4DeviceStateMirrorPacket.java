package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 面向当前持有该设备玩家的服务端权威 MP4 设备配置镜像。 */
public record MP4DeviceStateMirrorPacket(UUID deviceId, MP4StatePacket state, long updatedGameTime,
        boolean headphoneLinked, List<ItemStack> queue) implements CustomPacketPayload {
    public static final Type<MP4DeviceStateMirrorPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("mp4_device_state_mirror"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4DeviceStateMirrorPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MP4DeviceStateMirrorPacket decode(RegistryFriendlyByteBuf buffer) {
            UUID deviceId = buffer.readUUID();
            MP4StatePacket state = MP4StatePacket.STREAM_CODEC.decode(buffer);
            long updatedGameTime = buffer.readLong();
            boolean headphoneLinked = buffer.readBoolean();
            int size = Math.max(0, Math.min(MP4Item.MAX_QUEUE_SIZE, buffer.readVarInt()));
            List<ItemStack> queue = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                queue.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
            }
            return new MP4DeviceStateMirrorPacket(deviceId, state, updatedGameTime, headphoneLinked,
                    List.copyOf(queue));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, MP4DeviceStateMirrorPacket packet) {
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

    public static MP4DeviceStateMirrorPacket fromEntry(UUID deviceId, MP4DeviceStateStore.DeviceEntry entry,
            boolean headphoneLinked) {
        return new MP4DeviceStateMirrorPacket(deviceId, MP4StatePacket.fromState(entry.state(), deviceId),
                entry.updatedGameTime(), headphoneLinked, entry.queue());
    }

    public static void handle(MP4DeviceStateMirrorPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> MP4Client.receiveMirroredState(payload.deviceId(), payload.state().toState(),
                payload.updatedGameTime(), payload.headphoneLinked(), payload.queue()));
    }
}