package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** 聚焦手持界面的服务端权威 MP4 设备 ID 响应。 */
public record MP4DeviceIdPacket(InteractionHand hand, UUID deviceId) implements CustomPacketPayload {
    public static final Type<MP4DeviceIdPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, "mp4_device_id"));

    private static final StreamCodec<RegistryFriendlyByteBuf, InteractionHand> HAND_CODEC = new StreamCodec<>() {
        @Override
        public InteractionHand decode(RegistryFriendlyByteBuf buffer) {
            return buffer.readBoolean() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, InteractionHand value) {
            buffer.writeBoolean(value == InteractionHand.OFF_HAND);
        }
    };

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

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4DeviceIdPacket> STREAM_CODEC = StreamCodec.composite(
            HAND_CODEC, packet -> packet.hand(),
            UUID_CODEC, packet -> packet.deviceId(),
            (hand, deviceId) -> new MP4DeviceIdPacket(hand, deviceId));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MP4DeviceIdPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> MP4Client.receiveDeviceId(payload.hand(), payload.deviceId()));
    }
}
