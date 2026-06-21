package com.zhongbai233.net_music_can_play_bili.server;

import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.link.MediaBindingData.MediaSource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

public final class MediaBindingCleanupService {
    private MediaBindingCleanupService() {
    }

    public static ClearEquipmentResult clearEquipmentBindings(ServerPlayer player, ItemStack equipment) {
        if (player == null || equipment.isEmpty()) {
            return new ClearEquipmentResult(0, 0);
        }
        boolean holographic = HolographicGlassesAbility.has(equipment);
        boolean headphones = HeadphoneAbility.has(equipment);
        int glassesCount = holographic ? HolographicGlassesItem.clearAllBoundMedia(equipment) : 0;
        int headphoneCount = headphones ? AudioLinkData.clearHeadphoneLinks(equipment) : 0;
        if (headphones) {
            AudioLinkIndex.updatePlayerHeadphones(player);
        }
        if (glassesCount > 0 || headphoneCount > 0) {
            player.getInventory().setChanged();
            if (player.containerMenu != null) {
                player.containerMenu.broadcastChanges();
            }
        }
        return new ClearEquipmentResult(glassesCount, headphoneCount);
    }

    public static ClearEquipmentResult clearEquippedHeadBindings(ServerPlayer player) {
        if (player == null) {
            return new ClearEquipmentResult(0, 0);
        }
        MutableCounts counts = new MutableCounts();
        EquippedMediaItems.forEachEquipped(player, equipment -> {
            ClearEquipmentResult result = clearEquipmentBindings(player, equipment);
            counts.holographicCount += result.holographicCount();
            counts.headphoneCount += result.headphoneCount();
        });
        return new ClearEquipmentResult(counts.holographicCount, counts.headphoneCount);
    }

    public static TargetBindingStats countTargetBindings(ServerPlayer viewer, MediaSource source) {
        if (viewer == null || source == null) {
            return TargetBindingStats.EMPTY;
        }
        MinecraftServer server = viewer.level().getServer();
        if (server == null) {
            return TargetBindingStats.EMPTY;
        }
        Set<String> headphoneKeys = new HashSet<>();
        Set<String> holographicKeys = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            scanPlayerStacks(player, stack -> {
                if (isHeadphoneBoundTo(stack, source)) {
                    headphoneKeys.add(player.getUUID() + ":" + System.identityHashCode(stack));
                }
                if (HolographicGlassesItem.boundToMedia(stack, source)) {
                    holographicKeys.add(player.getUUID() + ":" + System.identityHashCode(stack));
                }
            });
        }
        return new TargetBindingStats(headphoneKeys.size(), holographicKeys.size());
    }

    public static TargetBindingStats clearTargetBindings(ServerPlayer actor, MediaSource source) {
        if (actor == null || source == null) {
            return TargetBindingStats.EMPTY;
        }
        MinecraftServer server = actor.level().getServer();
        if (server == null) {
            return TargetBindingStats.EMPTY;
        }
        Set<String> headphoneKeys = new HashSet<>();
        Set<String> holographicKeys = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean changed = false;
            MutableBoolean headphonesChanged = new MutableBoolean();
            MutableBoolean glassesChanged = new MutableBoolean();
            scanPlayerStacks(player, stack -> {
                if (clearHeadphoneBoundTo(stack, source)) {
                    headphonesChanged.value = true;
                    headphoneKeys.add(player.getUUID() + ":" + System.identityHashCode(stack));
                }
                if (HolographicGlassesItem.clearBoundMedia(stack, source)) {
                    glassesChanged.value = true;
                    holographicKeys.add(player.getUUID() + ":" + System.identityHashCode(stack));
                }
            });
            changed |= headphonesChanged.value || glassesChanged.value;
            if (headphonesChanged.value) {
                AudioLinkIndex.updatePlayerHeadphones(player);
            }
            if (changed) {
                player.getInventory().setChanged();
                if (player.containerMenu != null) {
                    player.containerMenu.broadcastChanges();
                }
            }
        }
        return new TargetBindingStats(headphoneKeys.size(), holographicKeys.size());
    }

    public static MediaSource mp4Source(UUID deviceId) {
        return MediaSource.mp4(deviceId);
    }

    public static MediaSource turntableSource(Level level, BlockPos pos) {
        return level != null && pos != null ? MediaSource.turntable(level.dimension(), pos) : null;
    }

    private static boolean isHeadphoneBoundTo(ItemStack stack, MediaSource source) {
        if (!HeadphoneAbility.has(stack) || source == null) {
            return false;
        }
        if (source.isMp4()) {
            return AudioLinkData.headphoneLinkedToMp4(stack, source.mp4DeviceId());
        }
        if (source.isTurntable()) {
            return source.pos() != null && source.pos().equals(AudioLinkData.readHeadphoneTurntable(stack));
        }
        return false;
    }

    private static boolean clearHeadphoneBoundTo(ItemStack stack, MediaSource source) {
        if (!isHeadphoneBoundTo(stack, source)) {
            return false;
        }
        if (source.isMp4()) {
            AudioLinkData.clearHeadphoneMp4(stack);
        } else {
            AudioLinkData.clearHeadphoneTurntable(stack);
        }
        return true;
    }

    private static void scanPlayerStacks(ServerPlayer player, StackConsumer consumer) {
        if (player == null || consumer == null) {
            return;
        }
        Set<ItemStack> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        EquippedMediaItems.forEachEquipped(player, stack -> acceptOnce(seen, consumer, stack));
        ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
        acceptOnce(seen, consumer, carried);
        if (player.containerMenu != null) {
            for (Slot slot : player.containerMenu.slots) {
                acceptOnce(seen, consumer, slot.getItem());
            }
        }
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            acceptOnce(seen, consumer, inventory.getItem(slot));
        }
    }

    private static void acceptOnce(Set<ItemStack> seen, StackConsumer consumer, ItemStack stack) {
        if (stack.isEmpty() || !seen.add(stack)) {
            return;
        }
        consumer.accept(stack);
    }

    @FunctionalInterface
    private interface StackConsumer {
        void accept(ItemStack stack);
    }

    private static final class MutableBoolean {
        private boolean value;
    }

    private static final class MutableCounts {
        private int holographicCount;
        private int headphoneCount;
    }

    public record ClearEquipmentResult(int holographicCount, int headphoneCount) {
        public int total() {
            return holographicCount + headphoneCount;
        }
    }

    public record TargetBindingStats(int headphoneCount, int holographicCount) {
        public static final TargetBindingStats EMPTY = new TargetBindingStats(0, 0);

        public int total() {
            return headphoneCount + holographicCount;
        }
    }
}
