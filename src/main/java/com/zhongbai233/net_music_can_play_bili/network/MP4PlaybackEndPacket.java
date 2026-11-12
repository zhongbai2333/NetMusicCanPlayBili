package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackEndReason;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;

/** Owner reports terminal media state for one exact server-issued physical transport. */
public record MP4PlaybackEndPacket(UUID sourceId, String sessionId, String transportToken, PlaybackEndReason reason)
        implements CustomPacketPayload {
    public static final Type<MP4PlaybackEndPacket> TYPE = new Type<>(NetworkPayloadIds.id("mp4_playback_end"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MP4PlaybackEndPacket> STREAM_CODEC = new StreamCodec<>() {
        public MP4PlaybackEndPacket decode(RegistryFriendlyByteBuf buffer) {
            UUID source = buffer.readUUID();
            String session = buffer.readUtf(128), token = buffer.readUtf(128);
            int reason = buffer.readVarInt();
            if (reason < 0 || reason >= PlaybackEndReason.values().length)
                throw new io.netty.handler.codec.DecoderException("invalid playback end reason");
            return new MP4PlaybackEndPacket(source, session, token, PlaybackEndReason.values()[reason]);
        }
        public void encode(RegistryFriendlyByteBuf buffer, MP4PlaybackEndPacket packet) {
            buffer.writeUUID(packet.sourceId());
            buffer.writeUtf(packet.sessionId(), 128);
            buffer.writeUtf(packet.transportToken(), 128);
            buffer.writeVarInt(packet.reason().ordinal());
        }
    };
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(MP4PlaybackEndPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player
                && NetworkRateLimiter.allow(player.getUUID(), "mp4_playback_end", 16)) {
            MP4PlaybackSyncManager.handleEnd(player, packet);
        }
    }
}
