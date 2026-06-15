package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.MP4ClientPlayback;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record MP4PlaybackSyncPacket(UUID ownerId, UUID sourceId, int sourceType, int sourceEntityId,
        double sourceX, double sourceY, double sourceZ, boolean playing, int queueIndex, String playUrl, String rawUrl,
    String songName, int durationSeconds, int volumePerMille, String sessionId, long elapsedMillis,
    boolean headphoneRouted)
        implements CustomPacketPayload {
    public static final int SOURCE_PLAYER = 0;
    public static final int SOURCE_ITEM = 1;
    public static final int SOURCE_BLOCK = 2;
    public static final int SOURCE_CONTAINER_ENTITY = 3;

    public static final Type<MP4PlaybackSyncPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, "mp4_playback_sync"));

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

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4PlaybackSyncPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MP4PlaybackSyncPacket decode(RegistryFriendlyByteBuf buffer) {
            return new MP4PlaybackSyncPacket(
                    UUID_CODEC.decode(buffer),
                    UUID_CODEC.decode(buffer),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readBoolean(),
                    buffer.readInt(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readUtf(),
                    buffer.readVarLong(),
                    buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, MP4PlaybackSyncPacket packet) {
            UUID_CODEC.encode(buffer, packet.ownerId());
            UUID_CODEC.encode(buffer, packet.sourceId());
            buffer.writeVarInt(packet.sourceType());
            buffer.writeVarInt(packet.sourceEntityId());
            buffer.writeDouble(packet.sourceX());
            buffer.writeDouble(packet.sourceY());
            buffer.writeDouble(packet.sourceZ());
            buffer.writeBoolean(packet.playing());
            buffer.writeInt(packet.queueIndex());
            buffer.writeUtf(packet.playUrl());
            buffer.writeUtf(packet.rawUrl());
            buffer.writeUtf(packet.songName());
            buffer.writeInt(packet.durationSeconds());
            buffer.writeInt(packet.volumePerMille());
            buffer.writeUtf(packet.sessionId());
            buffer.writeVarLong(packet.elapsedMillis());
            buffer.writeBoolean(packet.headphoneRouted());
        }
    };

    public static MP4PlaybackSyncPacket stop(UUID ownerId, int queueIndex) {
        return stop(ownerId, ownerId, queueIndex);
    }

    public static MP4PlaybackSyncPacket stop(UUID ownerId, UUID sourceId, int queueIndex) {
        UUID normalizedSourceId = sourceId != null ? sourceId : ownerId;
        return new MP4PlaybackSyncPacket(ownerId, normalizedSourceId, SOURCE_PLAYER, -1, 0.0D, 0.0D, 0.0D,
            false, queueIndex, "", "", "", 0, 0, "", 0L, false);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MP4PlaybackSyncPacket payload, IPayloadContext context) {
        MP4ClientPlayback.handleSync(payload);
    }
}