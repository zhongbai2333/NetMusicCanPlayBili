package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.bili.SpeakerAudioRelay;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioEndpointIndex;
import com.zhongbai233.net_music_can_play_bili.media.audio.IndexedAudioEndpoint;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.network.AudioEndpointSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client mirror of persistent endpoints; virtual relays do not require their chunks to be loaded. */
public final class ClientAudioEndpointIndex {
    private static final AudioEndpointIndex INDEX = new AudioEndpointIndex();
    private static final Map<PlaybackSourceId, BlockPos> SOURCE_POSITIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> ENDPOINT_POSITIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ENDPOINT_REVISIONS = new ConcurrentHashMap<>();
    private static final Map<PlaybackSourceId, Long> SOURCE_GENERATIONS = new ConcurrentHashMap<>();

    private ClientAudioEndpointIndex() {
    }

    public static void accept(AudioEndpointSnapshotPacket packet) {
        if (packet == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        PlaybackSourceId sourceId = PlaybackSourceId.of(packet.sourceId());
        Long currentGeneration = SOURCE_GENERATIONS.get(sourceId);
        if (packet.generation() > 0L && currentGeneration != null && packet.generation() <= currentGeneration) {
            return;
        }
        if (packet.reset()) {
            clearSource(sourceId);
        }
        packet.removals().forEach(ClientAudioEndpointIndex::removeEndpoint);
        if (!packet.subscribed()) {
            SOURCE_POSITIONS.remove(sourceId);
            if (packet.generation() > 0L) {
                SOURCE_GENERATIONS.put(sourceId, packet.generation());
            }
            return;
        }
        SOURCE_POSITIONS.put(sourceId, packet.sourcePos().immutable());
        String dimension = minecraft.level.dimension().identifier().toString();
        for (AudioEndpointSnapshotPacket.Endpoint endpoint : packet.endpoints()) {
            Long currentRevision = ENDPOINT_REVISIONS.get(endpoint.endpointId());
            if (currentRevision != null && currentRevision > endpoint.revision()) {
                continue;
            }
            BlockPos pos = endpoint.pos();
            BlockPos oldPos = ENDPOINT_POSITIONS.put(endpoint.endpointId(), pos);
            if (oldPos != null && !oldPos.equals(pos)) {
                ClientAudioOutputRegistry.clearMachineOverrideForSpeaker(oldPos);
            }
            ENDPOINT_REVISIONS.put(endpoint.endpointId(), endpoint.revision());
            ensureRelay(pos, packet.sourcePos(), endpoint);
            INDEX.upsert(new IndexedAudioEndpoint(endpoint.endpointId(), sourceId, dimension,
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                    endpoint.maxDistance(), endpoint.volume(), endpoint.volume(),
                    IndexedAudioEndpoint.Kind.SPEAKER, endpoint.revision()));
        }
        if (packet.generation() > 0L) {
            SOURCE_GENERATIONS.put(sourceId, packet.generation());
        }
    }

    public static Set<UUID> audibleDemands(PlaybackSourceId sourceId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return Set.of();
        }
        return INDEX.audibleDemands(sourceId, minecraft.level.dimension().identifier().toString(),
                minecraft.player.getX(), minecraft.player.getEyeY(), minecraft.player.getZ());
    }

    public static Set<UUID> anticipatedDemands(PlaybackSourceId sourceId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return Set.of();
        }
        var velocity = minecraft.player.getDeltaMovement();
        return INDEX.anticipatedDemands(sourceId, minecraft.level.dimension().identifier().toString(),
                minecraft.player.getX(), minecraft.player.getEyeY(), minecraft.player.getZ(),
                velocity.x, velocity.y, velocity.z);
    }

    public static BlockPos sourcePosition(PlaybackSourceId sourceId) {
        return sourceId != null ? SOURCE_POSITIONS.get(sourceId) : null;
    }

    public static List<IndexedAudioEndpoint> endpointSnapshot() {
        return INDEX.snapshot();
    }

    public static Map<PlaybackSourceId, BlockPos> sourcePositionSnapshot() {
        return Map.copyOf(SOURCE_POSITIONS);
    }

    public static void clear() {
        ENDPOINT_POSITIONS.values().forEach(ClientAudioOutputRegistry::clearMachineOverrideForSpeaker);
        ENDPOINT_POSITIONS.clear();
        ENDPOINT_REVISIONS.clear();
        SOURCE_POSITIONS.clear();
        SOURCE_GENERATIONS.clear();
        INDEX.clear();
    }

    private static void clearSource(PlaybackSourceId sourceId) {
        for (IndexedAudioEndpoint endpoint : INDEX.endpointsFor(sourceId)) {
            removeEndpoint(endpoint.endpointId());
        }
        INDEX.removeSource(sourceId);
        SOURCE_POSITIONS.remove(sourceId);
    }

    private static void removeEndpoint(UUID endpointId) {
        BlockPos oldPos = ENDPOINT_POSITIONS.remove(endpointId);
        ENDPOINT_REVISIONS.remove(endpointId);
        INDEX.remove(endpointId);
        if (oldPos != null) {
            ClientAudioOutputRegistry.clearMachineOverrideForSpeaker(oldPos);
        }
    }

    private static void ensureRelay(BlockPos speakerPos, BlockPos sourcePos,
            AudioEndpointSnapshotPacket.Endpoint endpoint) {
        if (!ClientAudioOutputRegistry.hasRelayAt(speakerPos)) {
            SpeakerAudioRelay relay = new SpeakerAudioRelay();
            ClientAudioOutputRegistry.registerRelay(speakerPos, sourcePos, relay);
        }
        ClientAudioOutputRegistry.updateRelayConfig(speakerPos, endpoint.channelIndex(), endpoint.volume(),
                endpoint.autoMixJoc(), endpoint.maxDistance());
    }
}
