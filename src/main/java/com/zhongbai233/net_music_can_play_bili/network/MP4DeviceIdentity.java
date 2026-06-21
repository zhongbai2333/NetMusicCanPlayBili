package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 服务端 MP4 设备 ID 管理：按需创建，并修复创造复制导致的重复 ID。 */
public final class MP4DeviceIdentity {
    private MP4DeviceIdentity() {
    }

    public static UUID getOrCreateUnique(ServerLevel level, ServerPlayer player, ItemStack stack) {
        if (!(stack.getItem() instanceof MP4Item)) {
            return null;
        }
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (player != null && deviceId != null && isDuplicateOccurrence(player, stack, deviceId)) {
            deviceId = replaceDeviceId(level, stack);
            markInventoryChanged(player);
            normalizeDuplicateDeviceIds(level, player);
            deviceId = MP4Item.readDeviceId(stack);
        }
        if (deviceId == null) {
            deviceId = MP4Item.getOrCreateDeviceId(stack);
            initializeDevice(level, deviceId, stack);
            markInventoryChanged(player);
        }
        initializeDevice(level, deviceId, stack);
        if (level != null && player != null) {
            MP4DeviceLocationIndex.recordPlayer(level, player, deviceId);
        }
        return deviceId;
    }

    public static UUID replaceDeviceId(ServerLevel level, ItemStack stack) {
        if (!(stack.getItem() instanceof MP4Item)) {
            return null;
        }
        UUID replacement = UUID.randomUUID();
        MP4Item.writeDeviceId(stack, replacement);
        initializeDevice(level, replacement, stack);
        return replacement;
    }

    public static UUID ensureUniqueForContainerSlot(ServerLevel level, ServerPlayer player, ItemStack stack) {
        UUID deviceId = getOrCreateUnique(level, player, stack);
        if (deviceId == null) {
            return null;
        }
        UUID afterNormalize = MP4Item.readDeviceId(stack);
        return afterNormalize != null ? afterNormalize : deviceId;
    }

    public static boolean normalizeDuplicateDeviceIds(ServerLevel level, ServerPlayer player) {
        if (player == null) {
            return false;
        }
        Set<UUID> seen = new HashSet<>();
        boolean changed = normalizeStack(level,
                player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY,
                seen);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            changed |= normalizeStack(level, inventory.getItem(slot), seen);
        }
        if (changed) {
            markInventoryChanged(player);
        }
        return changed;
    }

    private static boolean isDuplicateOccurrence(ServerPlayer player, ItemStack target, UUID deviceId) {
        if (deviceId == null) {
            return false;
        }
        ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
        if (carried == target) {
            return false;
        }
        if (carried.getItem() instanceof MP4Item && deviceId.equals(MP4Item.readDeviceId(carried))) {
            return target != carried;
        }
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == target) {
                return false;
            }
            if (stack.getItem() instanceof MP4Item && deviceId.equals(MP4Item.readDeviceId(stack))) {
                return true;
            }
        }
        return false;
    }

    private static boolean normalizeStack(ServerLevel level, ItemStack stack, Set<UUID> seen) {
        if (!(stack.getItem() instanceof MP4Item)) {
            return false;
        }
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId == null) {
            return false;
        }
        if (seen.add(deviceId)) {
            return false;
        }
        replaceDeviceId(level, stack);
        return true;
    }

    private static void initializeDevice(ServerLevel level, UUID deviceId, ItemStack stack) {
        if (level == null || deviceId == null || !(stack.getItem() instanceof MP4Item)) {
            return;
        }
        MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        MP4DeviceStateStore.syncQueueCopy(level, deviceId, stack);
    }

    private static void markInventoryChanged(ServerPlayer player) {
        if (player == null) {
            return;
        }
        player.getInventory().setChanged();
        if (player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
        }
    }
}