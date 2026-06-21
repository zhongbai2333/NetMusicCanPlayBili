package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.MP4ClientPlayback;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record MP4PlaybackVolumePacket(UUID ownerId, int volumePerMille) implements CustomPacketPayload {
    public static final Type<MP4PlaybackVolumePacket> TYPE = new Type<>(
            NetworkPayloadIds.id("mp4_playback_volume"));

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

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4PlaybackVolumePacket> STREAM_CODEC = StreamCodec
            .composite(
                    UUID_CODEC, packet -> packet.ownerId(),
                    ByteBufCodecs.INT, packet -> packet.volumePerMille(),
                    (ownerId, volumePerMille) -> new MP4PlaybackVolumePacket(ownerId,
                            volumePerMille == null ? 1000 : volumePerMille.intValue()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MP4PlaybackVolumePacket payload, IPayloadContext context) {
        MP4ClientPlayback.updateVolume(payload.ownerId(), payload.volumePerMille() / 1000.0F);
    }
}