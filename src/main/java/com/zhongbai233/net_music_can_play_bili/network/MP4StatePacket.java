package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public record MP4StatePacket(boolean playing, boolean shuffle, boolean videoEnabled, boolean landscape,
        int qualityIndex, int selectedQueueIndex, int volumePerMille, int repeatMode,
        boolean playlistOpen, int queueScrollOffset, boolean lyricsEnabled, int progressPerMille,
    boolean rotationHintShown, int subtitleMode, boolean subtitleAiEnabled, UUID deviceId,
    long clientUpdatedAtMillis, long clientSequence)
        implements CustomPacketPayload {

    public MP4StatePacket(boolean playing, boolean shuffle, boolean videoEnabled, boolean landscape,
            int qualityIndex, int selectedQueueIndex, int volumePerMille, int repeatMode,
            boolean playlistOpen, int queueScrollOffset, boolean lyricsEnabled, int progressPerMille,
            boolean rotationHintShown, int subtitleMode, boolean subtitleAiEnabled) {
        this(playing, shuffle, videoEnabled, landscape, qualityIndex, selectedQueueIndex, volumePerMille, repeatMode,
        playlistOpen, queueScrollOffset, lyricsEnabled, progressPerMille, rotationHintShown, subtitleMode,
        subtitleAiEnabled, null, 0L, 0L);
    }

    public static final Type<MP4StatePacket> TYPE = new Type<>(
            NetworkPayloadIds.id("mp4_state"));
    private static final Map<SyncKey, SyncVersion> LAST_CLIENT_SYNCS = new ConcurrentHashMap<>();

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4StatePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MP4StatePacket decode(RegistryFriendlyByteBuf buffer) {
            return new MP4StatePacket(buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readBoolean(), buffer.readInt(), buffer.readBoolean(), buffer.readInt(),
                    buffer.readBoolean(),
                    buffer.readInt(), buffer.readBoolean(), buffer.readBoolean() ? buffer.readUUID() : null,
                    buffer.readVarLong(), buffer.readVarLong());
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
            buffer.writeVarLong(Math.max(0L, packet.clientUpdatedAtMillis()));
            buffer.writeVarLong(Math.max(0L, packet.clientSequence()));
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
                state.rotationHintShown(), state.subtitleMode(), state.subtitleAiEnabled(), null, 0L, 0L);
    }

    public static MP4StatePacket fromState(MP4Item.State state, UUID deviceId) {
        return fromState(state, deviceId, 0L, 0L);
    }

    public static MP4StatePacket fromState(MP4Item.State state, UUID deviceId, long clientUpdatedAtMillis,
            long clientSequence) {
        return new MP4StatePacket(state.playing(), state.shuffle(), state.videoEnabled(), state.landscape(),
                state.qualityIndex(), state.selectedQueueIndex(), state.volumePerMille(), state.repeatMode(),
                state.playlistOpen(), state.queueScrollOffset(), state.lyricsEnabled(), state.progressPerMille(),
                state.rotationHintShown(), state.subtitleMode(), state.subtitleAiEnabled(), deviceId,
                clientUpdatedAtMillis, clientSequence);
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
        ServerLevel level = (ServerLevel) player.level();
        UUID deviceId = MP4DeviceIdentity.getOrCreateUnique(level, player, stack);
        if (deviceId == null) {
            return;
        }
        SyncKey syncKey = new SyncKey(player.getUUID(), deviceId);
        SyncVersion incoming = new SyncVersion(payload.clientUpdatedAtMillis(), payload.clientSequence());
        SyncVersion previous = LAST_CLIENT_SYNCS.get(syncKey);
        if (previous != null && incoming.compareTo(previous) < 0) {
            return;
        }
        LAST_CLIENT_SYNCS.put(syncKey, incoming);
        MP4DeviceStateStore.syncQueueCopy(level, deviceId, stack);
        MP4DeviceStateStore.DeviceEntry current = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        MP4Item.State state = payload.toState();
        int queueSize = MP4Item.queueSize(stack);
        if (queueSize <= 0) {
            queueSize = current.queue().size();
        }
        int selected = queueSize <= 0 ? 0 : Math.max(0, Math.min(queueSize - 1, state.selectedQueueIndex()));
        boolean selectedTrackChanged = selected != current.state().selectedQueueIndex();
        int activeQueueIndex = MP4PlaybackSyncManager.activeQueueIndex(deviceId);
        boolean activeTrackChanged = activeQueueIndex >= 0 && activeQueueIndex != selected;
        if (selected != state.selectedQueueIndex()) {
            state = new MP4Item.State(state.playing(), state.shuffle(), state.videoEnabled(), state.landscape(),
                    state.qualityIndex(), selected, state.queueScrollOffset(), state.volumePerMille(),
                    state.repeatMode(), state.playlistOpen(), state.lyricsEnabled(), state.subtitleMode(),
                    state.subtitleAiEnabled(), selected == current.state().selectedQueueIndex()
                            ? state.progressPerMille() : 0,
                    state.rotationHintShown());
        }
        MP4DeviceStateStore.update(level, deviceId, current.withState(state));
        if (activeTrackChanged || current.state().playing() && selectedTrackChanged) {
            MP4PlaybackControlPacket.restartSelected(player, stack, deviceId, selected, current.state().volumePerMille());
        }
    }

    private record SyncKey(UUID playerId, UUID deviceId) {
    }

    private record SyncVersion(long updatedAtMillis, long sequence) implements Comparable<SyncVersion> {
        @Override
        public int compareTo(SyncVersion other) {
            if (other == null) {
                return 1;
            }
            int timeCompare = Long.compare(updatedAtMillis, other.updatedAtMillis());
            return timeCompare != 0 ? timeCompare : Long.compare(sequence, other.sequence());
        }
    }
}
