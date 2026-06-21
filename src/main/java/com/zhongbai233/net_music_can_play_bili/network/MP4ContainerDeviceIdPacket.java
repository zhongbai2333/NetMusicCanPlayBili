package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/** 将服务端修正后的 MP4 DeviceID 回写到客户端当前容器槽位。 */
public record MP4ContainerDeviceIdPacket(int containerSlotIndex, UUID deviceId) implements CustomPacketPayload {
    public static final Type<MP4ContainerDeviceIdPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("mp4_container_device_id"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4ContainerDeviceIdPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MP4ContainerDeviceIdPacket decode(RegistryFriendlyByteBuf buffer) {
            return new MP4ContainerDeviceIdPacket(buffer.readInt(), buffer.readUUID());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, MP4ContainerDeviceIdPacket packet) {
            buffer.writeInt(packet.containerSlotIndex());
            buffer.writeUUID(packet.deviceId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MP4ContainerDeviceIdPacket payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> MP4Client.receiveContainerDeviceId(payload.containerSlotIndex(), payload.deviceId()));
    }
}