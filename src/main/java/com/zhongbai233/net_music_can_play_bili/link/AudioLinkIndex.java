package com.zhongbai233.net_music_can_play_bili.link;

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
    private static final Map<UUID, Set<UUID>> HEADPHONE_PLAYERS_BY_MP4 = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> MP4_BY_HEADPHONE_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<UUID>> HEADPHONE_OWNERS_BY_MP4 = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<UUID>> MP4S_BY_HEADPHONE_OWNER = new ConcurrentHashMap<>();

    private AudioLinkIndex() {
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
        Set<UUID> linkedMp4s = linkedMp4s(player);
        Set<UUID> previousLinks = MP4S_BY_HEADPHONE_OWNER.get(playerId);
        if (previousLinks != null) {
            for (UUID previous : previousLinks) {
                if (!linkedMp4s.contains(previous)) {
                    removeHeadphoneOwner(playerId, previous);
                }
            }
        }
        if (linkedMp4s.isEmpty()) {
            MP4S_BY_HEADPHONE_OWNER.remove(playerId);
        } else {
            MP4S_BY_HEADPHONE_OWNER.put(playerId, Set.copyOf(linkedMp4s));
            for (UUID linkedMp4 : linkedMp4s) {
                HEADPHONE_OWNERS_BY_MP4.computeIfAbsent(linkedMp4, ignored -> ConcurrentHashMap.newKeySet())
                        .add(playerId);
            }
        }

        UUID linkedMp4 = linkedMp4(player);
        UUID previous = MP4_BY_HEADPHONE_PLAYER.get(playerId);
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
        Set<UUID> players = deviceId != null ? HEADPHONE_PLAYERS_BY_MP4.get(deviceId) : null;
        return players != null ? Set.copyOf(players) : Collections.emptySet();
    }

    public static Set<UUID> removeHeadphonePlayersForMp4(UUID deviceId) {
        Set<UUID> players = deviceId != null ? HEADPHONE_PLAYERS_BY_MP4.remove(deviceId) : null;
        if (players == null || players.isEmpty()) {
            return Collections.emptySet();
        }
        Set<UUID> snapshot = Set.copyOf(players);
        for (UUID playerId : snapshot) {
            MP4_BY_HEADPHONE_PLAYER.remove(playerId, deviceId);
        }
        return snapshot;
    }

    public static boolean hasHeadphoneLinkedToMp4(UUID deviceId) {
        if (deviceId == null) {
            return false;
        }
        Set<UUID> owners = HEADPHONE_OWNERS_BY_MP4.get(deviceId);
        return owners != null && !owners.isEmpty();
    }

    public static void removeHeadphonePlayer(ServerPlayer player) {
        if (player != null) {
            removeHeadphonePlayer(player.getUUID());
        }
    }

    public static void removeHeadphonePlayer(UUID playerId) {
        UUID previous = playerId != null ? MP4_BY_HEADPHONE_PLAYER.remove(playerId) : null;
        if (previous != null) {
            removeHeadphonePlayer(playerId, previous);
        }
    }

    public static void removeHeadphoneOwner(UUID playerId) {
        Set<UUID> previous = playerId != null ? MP4S_BY_HEADPHONE_OWNER.remove(playerId) : null;
        if (previous != null) {
            for (UUID deviceId : previous) {
                removeHeadphoneOwner(playerId, deviceId);
            }
        }
    }

    public static void clear() {
        SPEAKERS_BY_TURNTABLE.clear();
        HEADPHONE_PLAYERS_BY_MP4.clear();
        MP4_BY_HEADPHONE_PLAYER.clear();
        HEADPHONE_OWNERS_BY_MP4.clear();
        MP4S_BY_HEADPHONE_OWNER.clear();
    }

    private static void removeHeadphonePlayer(UUID playerId, UUID deviceId) {
        if (playerId == null || deviceId == null) {
            return;
        }
        Set<UUID> players = HEADPHONE_PLAYERS_BY_MP4.get(deviceId);
        if (players == null) {
            return;
        }
        players.remove(playerId);
        if (players.isEmpty()) {
            HEADPHONE_PLAYERS_BY_MP4.remove(deviceId, players);
        }
    }

    private static void removeHeadphoneOwner(UUID playerId, UUID deviceId) {
        if (playerId == null || deviceId == null) {
            return;
        }
        Set<UUID> owners = HEADPHONE_OWNERS_BY_MP4.get(deviceId);
        if (owners == null) {
            return;
        }
        owners.remove(playerId);
        if (owners.isEmpty()) {
            HEADPHONE_OWNERS_BY_MP4.remove(deviceId, owners);
        }
    }

    private static UUID linkedMp4(ServerPlayer player) {
        ItemStack head = EquippedMediaItems.firstHeadphones(player);
        if (!HeadphoneAbility.has(head)) {
            return null;
        }
        return AudioLinkData.readHeadphoneMp4(head);
    }

    private static Set<UUID> linkedMp4s(ServerPlayer player) {
        Set<UUID> deviceIds = new HashSet<>();
        EquippedMediaItems.forEachEquipped(player, stack -> addLinkedMp4(deviceIds, stack));
        ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
        addLinkedMp4(deviceIds, carried);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            addLinkedMp4(deviceIds, inventory.getItem(slot));
        }
        return deviceIds;
    }

    private static void addLinkedMp4(Set<UUID> deviceIds, ItemStack stack) {
        if (!HeadphoneAbility.has(stack)) {
            return;
        }
        UUID deviceId = AudioLinkData.readHeadphoneMp4(stack);
        if (deviceId != null) {
            deviceIds.add(deviceId);
        }
    }
}
