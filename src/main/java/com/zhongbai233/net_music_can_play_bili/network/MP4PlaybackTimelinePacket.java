package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.MP4ClientMediaSync;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaTimelinePayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** MP4 播放轻量校时包，不重复携带直链 URL。 */
public record MP4PlaybackTimelinePacket(UUID sourceId, String sessionId, long elapsedMillis, int volumePerMille,
        boolean headphoneRouted) implements CustomPacketPayload, ClientMediaTimelinePayload {
    public static final Type<MP4PlaybackTimelinePacket> TYPE = new Type<>(
            NetworkPayloadIds.id("mp4_playback_timeline"));

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

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4PlaybackTimelinePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MP4PlaybackTimelinePacket decode(RegistryFriendlyByteBuf buffer) {
            return new MP4PlaybackTimelinePacket(UUID_CODEC.decode(buffer), buffer.readUtf(128),
                    buffer.readVarLong(), buffer.readVarInt(), buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, MP4PlaybackTimelinePacket packet) {
            UUID_CODEC.encode(buffer, packet.sourceId());
            buffer.writeUtf(packet.sessionId() == null ? "" : packet.sessionId(), 128);
            buffer.writeVarLong(Math.max(0L, packet.elapsedMillis()));
            buffer.writeVarInt(Math.max(0, Math.min(1000, packet.volumePerMille())));
            buffer.writeBoolean(packet.headphoneRouted());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MP4PlaybackTimelinePacket payload, IPayloadContext context) {
        MP4ClientMediaSync.handleTimeline(payload);
    }
}
