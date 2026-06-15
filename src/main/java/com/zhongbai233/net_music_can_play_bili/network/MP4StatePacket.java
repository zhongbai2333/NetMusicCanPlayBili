package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record MP4StatePacket(boolean playing, boolean shuffle, boolean videoEnabled, boolean landscape,
        int qualityIndex, int selectedQueueIndex, int volumePerMille, int repeatMode,
    boolean playlistOpen, int queueScrollOffset, boolean lyricsEnabled, int progressPerMille,
    boolean rotationHintShown, int subtitleMode, boolean subtitleAiEnabled, UUID deviceId) implements CustomPacketPayload {

    public MP4StatePacket(boolean playing, boolean shuffle, boolean videoEnabled, boolean landscape,
            int qualityIndex, int selectedQueueIndex, int volumePerMille, int repeatMode,
            boolean playlistOpen, int queueScrollOffset, boolean lyricsEnabled, int progressPerMille,
            boolean rotationHintShown, int subtitleMode, boolean subtitleAiEnabled) {
        this(playing, shuffle, videoEnabled, landscape, qualityIndex, selectedQueueIndex, volumePerMille, repeatMode,
                playlistOpen, queueScrollOffset, lyricsEnabled, progressPerMille, rotationHintShown, subtitleMode,
                subtitleAiEnabled, null);
    }

    public static final Type<MP4StatePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, "mp4_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4StatePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MP4StatePacket decode(RegistryFriendlyByteBuf buffer) {
            return new MP4StatePacket(buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readBoolean(), buffer.readInt(), buffer.readBoolean(), buffer.readInt(), buffer.readBoolean(),
                    buffer.readInt(), buffer.readBoolean(), buffer.readBoolean() ? buffer.readUUID() : null);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, MP4StatePacket packet) {
            buffer.writeBoolean(packet.playing());
            buffer.writeBoolean(packet.shuffle());
            buffer.writeBoolean(packet.videoEnabled());
            buffer.writeBoolean(packet.landscape());
            buffer.writeInt(packet.qualityIndex());
            buffer.writeInt(packet.selectedQueueIndex());
            buffer.writeInt(packet.volumePerMille());
            buffer.writeInt(packet.repeatMode());
            buffer.writeBoolean(packet.playlistOpen());
            buffer.writeInt(packet.queueScrollOffset());
            buffer.writeBoolean(packet.lyricsEnabled());
            buffer.writeInt(packet.progressPerMille());
            buffer.writeBoolean(packet.rotationHintShown());
            buffer.writeInt(packet.subtitleMode());
            buffer.writeBoolean(packet.subtitleAiEnabled());
            buffer.writeBoolean(packet.deviceId() != null);
            if (packet.deviceId() != null) {
                buffer.writeUUID(packet.deviceId());
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static MP4StatePacket fromState(MP4Item.State state) {
        return new MP4StatePacket(state.playing(), state.shuffle(), state.videoEnabled(), state.landscape(),
                state.qualityIndex(), state.selectedQueueIndex(), state.volumePerMille(), state.repeatMode(),
            state.playlistOpen(), state.queueScrollOffset(), state.lyricsEnabled(), state.progressPerMille(),
                state.rotationHintShown(), state.subtitleMode(), state.subtitleAiEnabled());
    }

    public static MP4StatePacket fromState(MP4Item.State state, UUID deviceId) {
        return new MP4StatePacket(state.playing(), state.shuffle(), state.videoEnabled(), state.landscape(),
                state.qualityIndex(), state.selectedQueueIndex(), state.volumePerMille(), state.repeatMode(),
            state.playlistOpen(), state.queueScrollOffset(), state.lyricsEnabled(), state.progressPerMille(),
                state.rotationHintShown(), state.subtitleMode(), state.subtitleAiEnabled(), deviceId);
    }

    public MP4Item.State toState() {
        return new MP4Item.State(playing, shuffle, videoEnabled, landscape, qualityIndex, selectedQueueIndex,
            queueScrollOffset, volumePerMille, repeatMode, playlistOpen, lyricsEnabled, subtitleMode,
            subtitleAiEnabled, progressPerMille, rotationHintShown);
    }

    public static void handle(MP4StatePacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = payload.deviceId() != null ? MP4Item.findByDeviceId(player, payload.deviceId())
            : MP4Item.findAnyInInventory(player);
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        UUID deviceId = payload.deviceId() != null ? payload.deviceId() : MP4Item.getOrCreateDeviceId(stack);
        ServerLevel level = (ServerLevel) player.level();
        MP4DeviceStateStore.DeviceEntry current = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        MP4Item.State state = payload.toState();
        MP4DeviceStateStore.update(level, deviceId, current.withState(state));
    }
}
