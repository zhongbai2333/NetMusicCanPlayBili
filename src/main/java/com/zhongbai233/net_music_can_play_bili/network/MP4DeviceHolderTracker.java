package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 跟踪当前持有 MP4 设备的玩家，并向他们镜像服务端权威配置。 */
public final class MP4DeviceHolderTracker {
    private static final int HELD_SYNC_INTERVAL_TICKS = 5;
    private static final Map<HolderKey, Integer> LAST_SENT = new ConcurrentHashMap<>();

    private MP4DeviceHolderTracker() {
    }

    public static void tick(MinecraftServer server) {
        if (server == null || server.getTickCount() % HELD_SYNC_INTERVAL_TICKS != 0) {
            return;
        }
        Set<HolderKey> active = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                scan(level, player, player.getItemInHand(InteractionHand.MAIN_HAND), active);
                scan(level, player, player.getItemInHand(InteractionHand.OFF_HAND), active);
                scan(level, player, player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY,
                        active);
            }
        }
        LAST_SENT.keySet().removeIf(key -> !active.contains(key));
    }

    public static void invalidate(UUID deviceId) {
        if (deviceId != null) {
            LAST_SENT.keySet().removeIf(key -> deviceId.equals(key.deviceId()));
        }
    }

    public static void clear() {
        LAST_SENT.clear();
    }

    private static void scan(ServerLevel level, ServerPlayer player, ItemStack stack, Set<HolderKey> active) {
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        UUID deviceId = MP4Item.getOrCreateDeviceId(stack);
        MP4DeviceStateStore.syncQueueCopy(level, deviceId, stack);
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        boolean headphoneLinked = AudioLinkIndex.hasHeadphoneLinkedToMp4(deviceId);
        HolderKey key = new HolderKey(player.getUUID(), deviceId);
        active.add(key);
        int fingerprint = fingerprint(entry, headphoneLinked);
        if (Objects.equals(LAST_SENT.get(key), fingerprint)) {
            return;
        }
        LAST_SENT.put(key, fingerprint);
        PacketDistributor.sendToPlayer(player, MP4DeviceStateMirrorPacket.fromEntry(deviceId, entry, headphoneLinked));
    }

    private static int fingerprint(MP4DeviceStateStore.DeviceEntry entry, boolean headphoneLinked) {
        int hash = entry.state().hashCode();
        hash = 31 * hash + Boolean.hashCode(headphoneLinked);
        hash = 31 * hash + Long.hashCode(entry.updatedGameTime());
        hash = 31 * hash + Long.hashCode(entry.elapsedMillis());
        hash = 31 * hash + entry.durationSeconds();
        hash = 31 * hash + entry.sessionId().hashCode();
        for (ItemStack stack : entry.queue()) {
            hash = 31 * hash + stack.getItem().hashCode();
            hash = 31 * hash + stack.getComponents().hashCode();
        }
        return hash;
    }

    private record HolderKey(UUID playerId, UUID deviceId) {
    }
}