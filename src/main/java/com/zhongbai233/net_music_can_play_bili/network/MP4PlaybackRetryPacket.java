package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.UUID;

/** Requests a fresh MP4 transport URL while retaining the active logical session. */
public record MP4PlaybackRetryPacket(UUID deviceId, String expectedSessionId, long targetMillis)
        implements CustomPacketPayload {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_SESSION_ID_LENGTH = 128;

    public static final Type<MP4PlaybackRetryPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("mp4_playback_retry"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MP4PlaybackRetryPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MP4PlaybackRetryPacket decode(RegistryFriendlyByteBuf buffer) {
                    return new MP4PlaybackRetryPacket(buffer.readUUID(), buffer.readUtf(MAX_SESSION_ID_LENGTH),
                            buffer.readVarLong());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, MP4PlaybackRetryPacket packet) {
                    buffer.writeUUID(packet.deviceId());
                    buffer.writeUtf(packet.expectedSessionId() != null ? packet.expectedSessionId() : "",
                            MAX_SESSION_ID_LENGTH);
                    buffer.writeVarLong(Math.max(0L, packet.targetMillis()));
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MP4PlaybackRetryPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || payload == null || payload.deviceId() == null) {
            return;
        }
        PlaybackSessionId expectedSessionId = PlaybackSessionId.parse(payload.expectedSessionId()).orElse(null);
        if (expectedSessionId == null) {
            return;
        }
        if (!NetworkRateLimiter.allow(player.getUUID(), "mp4_playback_retry", 4)) {
            LOGGER.debug("丢弃过频 MP4 播放重试包: player={} device={} session={}", player.getUUID(),
                    payload.deviceId(), expectedSessionId);
            return;
        }
        ItemStack stack = MP4Item.findByDeviceId(player, payload.deviceId());
        if (stack == null || !(stack.getItem() instanceof MP4Item)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        UUID authoritativeDeviceId = MP4DeviceIdentity.getOrCreateUnique(level, player, stack);
        if (!payload.deviceId().equals(authoritativeDeviceId)) {
            return;
        }
        MP4PlaybackControlPacket.retryPlayback(player, stack, payload.deviceId(), expectedSessionId,
                Math.max(0L, payload.targetMillis()));
    }
}
