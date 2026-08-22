package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioEndpointIndex;
import com.zhongbai233.net_music_can_play_bili.link.AudioPlaybackIndexSavedData;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Spatial subscription snapshot/delta for one playback source. */
public record AudioEndpointSnapshotPacket(UUID sourceId, BlockPos sourcePos, long generation,
        boolean reset, boolean subscribed, List<Endpoint> endpoints, List<UUID> removals)
        implements CustomPacketPayload {
    private static final int MAX_ENDPOINTS = 4096;
    public static final Type<AudioEndpointSnapshotPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("audio_endpoint_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AudioEndpointSnapshotPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public AudioEndpointSnapshotPacket decode(RegistryFriendlyByteBuf buffer) {
                    UUID sourceId = buffer.readUUID();
                    BlockPos sourcePos = BlockPos.STREAM_CODEC.decode(buffer);
                    long generation = buffer.readVarLong();
                    boolean reset = buffer.readBoolean();
                    boolean subscribed = buffer.readBoolean();
                    int count = buffer.readVarInt();
                    if (count < 0 || count > MAX_ENDPOINTS) {
                        throw new IllegalArgumentException("invalid audio endpoint snapshot size: " + count);
                    }
                    List<Endpoint> endpoints = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        endpoints.add(Endpoint.decode(buffer));
                    }
                    int removalCount = buffer.readVarInt();
                    if (removalCount < 0 || removalCount > MAX_ENDPOINTS) {
                        throw new IllegalArgumentException("invalid audio endpoint removal size: " + removalCount);
                    }
                    List<UUID> removals = new ArrayList<>(removalCount);
                    for (int index = 0; index < removalCount; index++) {
                        removals.add(buffer.readUUID());
                    }
                    return new AudioEndpointSnapshotPacket(sourceId, sourcePos, generation,
                            reset, subscribed, endpoints, removals);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, AudioEndpointSnapshotPacket packet) {
                    buffer.writeUUID(packet.sourceId());
                    BlockPos.STREAM_CODEC.encode(buffer, packet.sourcePos());
                    buffer.writeVarLong(packet.generation());
                    buffer.writeBoolean(packet.reset());
                    buffer.writeBoolean(packet.subscribed());
                    buffer.writeVarInt(packet.endpoints().size());
                    packet.endpoints().forEach(endpoint -> endpoint.encode(buffer));
                    buffer.writeVarInt(packet.removals().size());
                    packet.removals().forEach(buffer::writeUUID);
                }
            };

    public AudioEndpointSnapshotPacket {
        Objects.requireNonNull(sourceId, "sourceId");
        sourcePos = Objects.requireNonNull(sourcePos, "sourcePos").immutable();
        endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
        removals = List.copyOf(Objects.requireNonNull(removals, "removals"));
        generation = Math.max(0L, generation);
        if (endpoints.size() > MAX_ENDPOINTS || removals.size() > MAX_ENDPOINTS) {
            throw new IllegalArgumentException("too many audio endpoints");
        }
    }

    /** Compatibility constructor for bench fixtures and complete local snapshots. */
    public AudioEndpointSnapshotPacket(UUID sourceId, BlockPos sourcePos, List<Endpoint> endpoints) {
        this(sourceId, sourcePos, 0L, true, true, endpoints, List.of());
    }

    public static AudioEndpointSnapshotPacket from(PlaybackSourceId sourceId, BlockPos sourcePos,
            List<AudioPlaybackIndexSavedData.EndpointEntry> entries) {
        return delta(sourceId, sourcePos, 0L, true, true, entries, List.of());
    }

    public static AudioEndpointSnapshotPacket delta(PlaybackSourceId sourceId, BlockPos sourcePos,
            long generation, boolean reset, boolean subscribed,
            List<AudioPlaybackIndexSavedData.EndpointEntry> upserts, List<UUID> removals) {
        return new AudioEndpointSnapshotPacket(sourceId.value(), sourcePos, generation, reset, subscribed,
                upserts.stream().map(Endpoint::from).toList(), removals);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AudioEndpointSnapshotPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientAudioEndpointIndex.accept(payload));
    }

    public record Endpoint(UUID endpointId, BlockPos pos, int channelIndex, float volume,
            boolean autoMixJoc, float maxDistance, long revision) {
        public Endpoint {
            Objects.requireNonNull(endpointId, "endpointId");
            pos = Objects.requireNonNull(pos, "pos").immutable();
            volume = Math.clamp(volume, 0.0F, 2.0F);
            maxDistance = Math.clamp(maxDistance, 1.0F, 256.0F);
            revision = Math.max(0L, revision);
        }

        static Endpoint from(AudioPlaybackIndexSavedData.EndpointEntry entry) {
            return new Endpoint(entry.endpointId(), BlockPos.of(entry.endpointPos()), entry.channelIndex(),
                    entry.volume(), entry.autoMixJoc(), entry.maxDistance(), entry.revision());
        }

        static Endpoint decode(RegistryFriendlyByteBuf buffer) {
            return new Endpoint(buffer.readUUID(), BlockPos.STREAM_CODEC.decode(buffer), buffer.readVarInt(),
                    buffer.readFloat(), buffer.readBoolean(), buffer.readFloat(), buffer.readVarLong());
        }

        void encode(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(endpointId);
            BlockPos.STREAM_CODEC.encode(buffer, pos);
            buffer.writeVarInt(channelIndex);
            buffer.writeFloat(volume);
            buffer.writeBoolean(autoMixJoc);
            buffer.writeFloat(maxDistance);
            buffer.writeVarLong(revision);
        }
    }
}
