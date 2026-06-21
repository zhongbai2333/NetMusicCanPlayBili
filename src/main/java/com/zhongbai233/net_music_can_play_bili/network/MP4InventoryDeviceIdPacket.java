package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** 将服务端权威 MP4 DeviceID 回写到客户端玩家背包槽位。 */
public record MP4InventoryDeviceIdPacket(int inventorySlot, UUID deviceId) implements CustomPacketPayload {
    public static final Type<MP4InventoryDeviceIdPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("mp4_inventory_device_id"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4InventoryDeviceIdPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MP4InventoryDeviceIdPacket decode(RegistryFriendlyByteBuf buffer) {
            return new MP4InventoryDeviceIdPacket(buffer.readInt(), buffer.readUUID());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, MP4InventoryDeviceIdPacket packet) {
            buffer.writeInt(packet.inventorySlot());
            buffer.writeUUID(packet.deviceId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MP4InventoryDeviceIdPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> MP4Client.receiveInventoryDeviceId(payload.inventorySlot(), payload.deviceId()));
    }
}