package com.zhongbai233.net_music_can_play_bili.link;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.audio.IndexedAudioEndpoint;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.IndexedBlockPlaybackSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 音频路由链接的服务端反向索引。 */
public final class AudioLinkIndex {
    private static final Map<ResourceKey<Level>, Map<BlockPos, Set<BlockPos>>> SPEAKERS_BY_TURNTABLE = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Map<BlockPos, Set<BlockPos>>> VIDEO_PROJECTORS_BY_TURNTABLE = new ConcurrentHashMap<>();
    private static final Map<PlaybackSourceId, Set<UUID>> HEADPHONE_PLAYERS_BY_MP4 = new ConcurrentHashMap<>();
    private static final Map<UUID, PlaybackSourceId> MP4_BY_HEADPHONE_PLAYER = new ConcurrentHashMap<>();
    private static final Map<PlaybackSourceId, Set<UUID>> HEADPHONE_OWNERS_BY_MP4 = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<PlaybackSourceId>> MP4S_BY_HEADPHONE_OWNER = new ConcurrentHashMap<>();

    private AudioLinkIndex() {
    }

    public static void registerPlaybackSource(ServerLevel level, BlockPos sourcePos, PlaybackSourceId sourceId,
            AudioPlaybackIndexSavedData.SourceKind kind) {
        if (level != null && sourcePos != null && sourceId != null && kind != null) {
            AudioPlaybackIndexSavedData.get(level).upsertSource(sourcePos, sourceId, kind);
        }
    }

    public static void removePlaybackSource(ServerLevel level, BlockPos sourcePos) {
        if (level != null && sourcePos != null) {
            AudioPlaybackIndexSavedData.get(level).sourceAt(sourcePos).ifPresent(source ->
                    IndexedBlockPlaybackSessionManager.remove(level, source.playbackSourceId()));
            AudioPlaybackIndexSavedData.get(level).removeSource(sourcePos);
        }
    }

    public static void registerSpeaker(ServerLevel level, BlockPos speakerPos, BlockPos turntablePos) {
        if (level == null || speakerPos == null || turntablePos == null) {
            return;
        }
        unregisterSpeaker(level, speakerPos);
        SPEAKERS_BY_TURNTABLE
                .computeIfAbsent(level.dimension(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(turntablePos.immutable(), ignored -> ConcurrentHashMap.newKeySet())
                .add(speakerPos.immutable());
    }

    public static void upsertSpeakerEndpoint(ServerLevel level, UUID endpointId, BlockPos speakerPos,
            BlockPos sourcePos, int channelIndex, float volume, boolean autoMixJoc,
            float maxDistance, long revision) {
        if (level == null || endpointId == null || speakerPos == null || sourcePos == null) {
            return;
        }
        registerSpeaker(level, speakerPos, sourcePos);
        ensurePlaybackSourceAt(level, sourcePos);
        AudioPlaybackIndexSavedData.SourceEntry source = AudioPlaybackIndexSavedData.get(level)
                .sourceAt(sourcePos).orElse(null);
        AudioPlaybackIndexSavedData.get(level).upsertEndpoint(new AudioPlaybackIndexSavedData.EndpointEntry(
                endpointId, source != null ? source.sourceId() : "", sourcePos.asLong(), speakerPos.asLong(),
                channelIndex, volume, autoMixJoc, maxDistance, revision));
    }

    public static PlaybackSourceId ensurePlaybackSourceAt(ServerLevel level, BlockPos sourcePos) {
        if (level == null || sourcePos == null) {
            return null;
        }
        Object blockEntity = level.getBlockEntity(sourcePos);
        if (blockEntity instanceof ModernTurntableBlockEntity turntable) {
            PlaybackSourceId sourceId = turntable.getPlaybackSourceId();
            registerPlaybackSource(level, sourcePos, sourceId,
                    AudioPlaybackIndexSavedData.SourceKind.TURNTABLE);
            return sourceId;
        }
        if (blockEntity instanceof LiveStreamerBlockEntity liveStreamer) {
            PlaybackSourceId sourceId = liveStreamer.getPlaybackSourceId();
            registerPlaybackSource(level, sourcePos, sourceId,
                    AudioPlaybackIndexSavedData.SourceKind.LIVE_STREAMER);
            return sourceId;
        }
        return AudioPlaybackIndexSavedData.get(level).sourceAt(sourcePos)
                .map(source -> source.playbackSourceId()).orElse(null);
    }

    public static void removeSpeakerEndpoint(ServerLevel level, UUID endpointId, BlockPos speakerPos) {
        if (level == null) {
            return;
        }
        if (endpointId != null) {
            AudioPlaybackIndexSavedData.get(level).removeEndpoint(endpointId);
        } else if (speakerPos != null) {
            AudioPlaybackIndexSavedData.get(level).removeEndpointAt(speakerPos);
        }
        unregisterSpeaker(level, speakerPos);
    }

    public static java.util.List<AudioPlaybackIndexSavedData.EndpointEntry> speakerEndpointsFor(
            ServerLevel level, PlaybackSourceId sourceId) {
        return level != null && sourceId != null
                ? AudioPlaybackIndexSavedData.get(level).endpointsFor(sourceId)
                : java.util.List.of();
    }

    public static java.util.List<IndexedAudioEndpoint> indexedSpeakerEndpointsFor(
            ServerLevel level, PlaybackSourceId sourceId) {
        if (level == null || sourceId == null) {
            return java.util.List.of();
        }
        String dimension = level.dimension().identifier().toString();
        return speakerEndpointsFor(level, sourceId).stream()
                .map(endpoint -> endpoint.toIndexed(dimension).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public static void unregisterSpeaker(ServerLevel level, BlockPos speakerPos) {
        if (level == null || speakerPos == null) {
            return;
        }
        Map<BlockPos, Set<BlockPos>> byTurntable = SPEAKERS_BY_TURNTABLE.get(level.dimension());
        if (byTurntable == null) {
            return;
        }
        BlockPos immutableSpeakerPos = speakerPos.immutable();
        byTurntable.entrySet().removeIf(entry -> {
            entry.getValue().remove(immutableSpeakerPos);
            return entry.getValue().isEmpty();
        });
        if (byTurntable.isEmpty()) {
            SPEAKERS_BY_TURNTABLE.remove(level.dimension(), byTurntable);
        }
    }

    public static boolean hasSpeakerLinkedTo(ServerLevel level, BlockPos turntablePos) {
        if (level == null || turntablePos == null) {
            return false;
        }
        PlaybackSourceId sourceId = ensurePlaybackSourceAt(level, turntablePos);
        if (sourceId != null && !AudioPlaybackIndexSavedData.get(level).endpointsFor(sourceId).isEmpty()) {
            return true;
        }
        Map<BlockPos, Set<BlockPos>> byTurntable = SPEAKERS_BY_TURNTABLE.get(level.dimension());
        Set<BlockPos> speakers = byTurntable != null ? byTurntable.get(turntablePos) : null;
        return speakers != null && !speakers.isEmpty();
    }

    public static void registerVideoProjector(ServerLevel level, BlockPos projectorPos, BlockPos turntablePos) {
        if (level == null || projectorPos == null || turntablePos == null) {
            return;
        }
        unregisterVideoProjector(level, projectorPos);
        VIDEO_PROJECTORS_BY_TURNTABLE
                .computeIfAbsent(level.dimension(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(turntablePos.immutable(), ignored -> ConcurrentHashMap.newKeySet())
                .add(projectorPos.immutable());
    }

    public static void unregisterVideoProjector(ServerLevel level, BlockPos projectorPos) {
        if (level == null || projectorPos == null) {
            return;
        }
        Map<BlockPos, Set<BlockPos>> byTurntable = VIDEO_PROJECTORS_BY_TURNTABLE.get(level.dimension());
        if (byTurntable == null) {
            return;
        }
        BlockPos immutableProjectorPos = projectorPos.immutable();
        byTurntable.entrySet().removeIf(entry -> {
            entry.getValue().remove(immutableProjectorPos);
            return entry.getValue().isEmpty();
        });
        if (byTurntable.isEmpty()) {
            VIDEO_PROJECTORS_BY_TURNTABLE.remove(level.dimension(), byTurntable);
        }
    }

    public static boolean hasVideoProjectorLinkedTo(ServerLevel level, BlockPos turntablePos) {
        if (level == null || turntablePos == null) {
            return false;
        }
        Map<BlockPos, Set<BlockPos>> byTurntable = VIDEO_PROJECTORS_BY_TURNTABLE.get(level.dimension());
        Set<BlockPos> projectors = byTurntable != null ? byTurntable.get(turntablePos) : null;
        return projectors != null && !projectors.isEmpty();
    }

    public static void updatePlayerHeadphones(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUUID();
        Set<PlaybackSourceId> linkedMp4s = linkedMediaDevices(player);
        Set<PlaybackSourceId> previousLinks = MP4S_BY_HEADPHONE_OWNER.get(playerId);
        if (previousLinks != null) {
            for (PlaybackSourceId previous : previousLinks) {
                if (!linkedMp4s.contains(previous)) {
                    removeHeadphoneOwner(playerId, previous);
                }
            }
        }
        if (linkedMp4s.isEmpty()) {
            MP4S_BY_HEADPHONE_OWNER.remove(playerId);
        } else {
            MP4S_BY_HEADPHONE_OWNER.put(playerId, Set.copyOf(linkedMp4s));
            for (PlaybackSourceId linkedMp4 : linkedMp4s) {
                HEADPHONE_OWNERS_BY_MP4.computeIfAbsent(linkedMp4, ignored -> ConcurrentHashMap.newKeySet())
                        .add(playerId);
            }
        }

        PlaybackSourceId linkedMp4 = linkedMediaDevice(player);
        PlaybackSourceId previous = MP4_BY_HEADPHONE_PLAYER.get(playerId);
        if (previous != null && !previous.equals(linkedMp4)) {
            removeHeadphonePlayer(playerId, previous);
        }
        if (linkedMp4 != null) {
            MP4_BY_HEADPHONE_PLAYER.put(playerId, linkedMp4);
            HEADPHONE_PLAYERS_BY_MP4.computeIfAbsent(linkedMp4, ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
        } else {
            MP4_BY_HEADPHONE_PLAYER.remove(playerId);
        }
    }

    public static Set<UUID> headphonePlayersForMp4(UUID deviceId) {
        Set<UUID> players = deviceId != null
                ? HEADPHONE_PLAYERS_BY_MP4.get(PlaybackSourceId.of(deviceId))
                : null;
        return players != null ? Set.copyOf(players) : Collections.emptySet();
    }

    public static Set<UUID> removeHeadphonePlayersForMp4(UUID deviceId) {
        PlaybackSourceId sourceId = deviceId != null ? PlaybackSourceId.of(deviceId) : null;
        Set<UUID> players = sourceId != null ? HEADPHONE_PLAYERS_BY_MP4.remove(sourceId) : null;
        if (players == null || players.isEmpty()) {
            return Collections.emptySet();
        }
        Set<UUID> snapshot = Set.copyOf(players);
        for (UUID playerId : snapshot) {
            MP4_BY_HEADPHONE_PLAYER.remove(playerId, sourceId);
        }
        return snapshot;
    }

    public static boolean hasHeadphoneLinkedToMp4(UUID deviceId) {
        if (deviceId == null) {
            return false;
        }
        Set<UUID> owners = HEADPHONE_OWNERS_BY_MP4.get(PlaybackSourceId.of(deviceId));
        return owners != null && !owners.isEmpty();
    }

    public static void removeHeadphonePlayer(ServerPlayer player) {
        if (player != null) {
            removeHeadphonePlayer(player.getUUID());
        }
    }

    public static void removeHeadphonePlayer(UUID playerId) {
        PlaybackSourceId previous = playerId != null ? MP4_BY_HEADPHONE_PLAYER.remove(playerId) : null;
        if (previous != null) {
            removeHeadphonePlayer(playerId, previous);
        }
    }

    public static void removeHeadphoneOwner(UUID playerId) {
        Set<PlaybackSourceId> previous = playerId != null ? MP4S_BY_HEADPHONE_OWNER.remove(playerId) : null;
        if (previous != null) {
            for (PlaybackSourceId sourceId : previous) {
                removeHeadphoneOwner(playerId, sourceId);
            }
        }
    }

    public static void clear() {
        SPEAKERS_BY_TURNTABLE.clear();
        VIDEO_PROJECTORS_BY_TURNTABLE.clear();
        HEADPHONE_PLAYERS_BY_MP4.clear();
        MP4_BY_HEADPHONE_PLAYER.clear();
        HEADPHONE_OWNERS_BY_MP4.clear();
        MP4S_BY_HEADPHONE_OWNER.clear();
    }

    private static void removeHeadphonePlayer(UUID playerId, PlaybackSourceId sourceId) {
        if (playerId == null || sourceId == null) {
            return;
        }
        Set<UUID> players = HEADPHONE_PLAYERS_BY_MP4.get(sourceId);
        if (players == null) {
            return;
        }
        players.remove(playerId);
        if (players.isEmpty()) {
            HEADPHONE_PLAYERS_BY_MP4.remove(sourceId, players);
        }
    }

    private static void removeHeadphoneOwner(UUID playerId, PlaybackSourceId sourceId) {
        if (playerId == null || sourceId == null) {
            return;
        }
        Set<UUID> owners = HEADPHONE_OWNERS_BY_MP4.get(sourceId);
        if (owners == null) {
            return;
        }
        owners.remove(playerId);
        if (owners.isEmpty()) {
            HEADPHONE_OWNERS_BY_MP4.remove(sourceId, owners);
        }
    }

    private static PlaybackSourceId linkedMediaDevice(ServerPlayer player) {
        ItemStack head = EquippedMediaItems.firstHeadphones(player);
        if (!HeadphoneAbility.has(head)) {
            return null;
        }
        UUID deviceId = AudioLinkData.readHeadphoneMediaDevice(head);
        return deviceId != null ? PlaybackSourceId.of(deviceId) : null;
    }

    private static Set<PlaybackSourceId> linkedMediaDevices(ServerPlayer player) {
        Set<PlaybackSourceId> deviceIds = new HashSet<>();
        EquippedMediaItems.forEachEquipped(player, stack -> addLinkedMediaDevice(deviceIds, stack));
        ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
        addLinkedMediaDevice(deviceIds, carried);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            addLinkedMediaDevice(deviceIds, inventory.getItem(slot));
        }
        return deviceIds;
    }

    private static void addLinkedMediaDevice(Set<PlaybackSourceId> deviceIds, ItemStack stack) {
        if (!HeadphoneAbility.has(stack)) {
            return;
        }
        UUID deviceId = AudioLinkData.readHeadphoneMediaDevice(stack);
        if (deviceId != null) {
            deviceIds.add(PlaybackSourceId.of(deviceId));
        }
    }
}
