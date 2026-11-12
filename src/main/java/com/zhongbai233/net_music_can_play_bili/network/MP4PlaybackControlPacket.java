package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.UUID;

public record MP4PlaybackControlPacket(Action action, int selectedQueueIndex, int volumePerMille, long targetMillis,
        UUID deviceId)
        implements CustomPacketPayload {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<MP4PlaybackControlPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("mp4_playback_control"));

    public MP4PlaybackControlPacket(Action action, int selectedQueueIndex, int volumePerMille, long targetMillis) {
        this(action, selectedQueueIndex, volumePerMille, targetMillis, null);
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, Action> ACTION_CODEC = new StreamCodec<>() {
        @Override
        public Action decode(RegistryFriendlyByteBuf buffer) {
            return Action.byId(buffer.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, Action action) {
            buffer.writeVarInt(action.id());
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4PlaybackControlPacket> STREAM_CODEC = StreamCodec
            .of((buffer, packet) -> {
                ACTION_CODEC.encode(buffer, packet.action());
                buffer.writeInt(packet.selectedQueueIndex());
                buffer.writeInt(packet.volumePerMille());
                buffer.writeVarLong(packet.targetMillis());
                buffer.writeBoolean(packet.deviceId() != null);
                if (packet.deviceId() != null) {
                    buffer.writeUUID(packet.deviceId());
                }
            }, buffer -> new MP4PlaybackControlPacket(ACTION_CODEC.decode(buffer), buffer.readInt(), buffer.readInt(),
                    buffer.readVarLong(), buffer.readBoolean() ? buffer.readUUID() : null));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MP4PlaybackControlPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!NetworkRateLimiter.allow(player.getUUID(), "mp4_playback_control", 8)) {
            LOGGER.debug("丢弃过频 MP4 播放控制包: player={} action={}", player.getUUID(),
                    payload.action());
            return;
        }
        ItemStack stack = Objects.requireNonNull(
                payload.deviceId() != null ? MP4Item.findByDeviceId(player, payload.deviceId())
                : MP4Item.findPlayableInInventory(player), "item stack");
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }

        ServerLevel level = (ServerLevel) player.level();
        UUID deviceId = MP4DeviceIdentity.getOrCreateUnique(level, player, stack);
        if (deviceId == null) {
            return;
        }
        MP4PlaybackCommands.execute(player, level, stack, deviceId, payload.action(),
                payload.selectedQueueIndex(), payload.volumePerMille(), payload.targetMillis());
    }

    public static void restartSelected(ServerPlayer player, ItemStack stack, UUID deviceId, int index,
            int volumePerMille) {
        MP4PlaybackCommands.restartSelected(player, stack, deviceId, index, volumePerMille);
    }

    public enum Action {
        START,
        PAUSE,
        STOP,
        RESTART,
        SEEK,
        VOLUME,
        SELECT,
        NEXT,
        PREVIOUS,
        SEEK_PAUSED;

        public int id() {
            return ordinal();
        }

        public static Action byId(int id) {
            Action[] values = values();
            return id >= 0 && id < values.length ? values[id] : START;
        }
    }
}
