package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.gui.PadFocusScreen;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.network.PadStatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PadClient {
    private static final int FAST_SYNC_INTERVAL_TICKS = 10;
    private static final int AUTO_PLAY_SCAN_INTERVAL_TICKS = 5;
    private static final int PAD_INDEX_REBUILD_INTERVAL_TICKS = 20;
    private static final Map<UUID, PadDocument> DOCUMENTS = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingDocumentSync> PENDING_SYNCS = new ConcurrentHashMap<>();
    private static final Map<UUID, List<IndexedPadStack>> PAD_INDEX = new HashMap<>();
    private static long localSequence;
    private static int fastSyncTicks;
    private static int autoPlayScanTicks;
    private static int padIndexRebuildTicks;
    private static boolean syncRequested;

    private PadClient() {
    }

    public static void tickHeldDeviceSync() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            clearCachedDocuments();
            return;
        }
        if (fastSyncTicks > 0) {
            fastSyncTicks--;
        }
        if (padIndexRebuildTicks > 0) {
            padIndexRebuildTicks--;
        } else {
            rebuildPadIndex(minecraft);
        }
        if (syncRequested && fastSyncTicks <= 0) {
            sendPendingSyncs(minecraft);
        }
        if (autoPlayScanTicks > 0) {
            autoPlayScanTicks--;
            return;
        }
        autoPlayScanTicks = AUTO_PLAY_SCAN_INTERVAL_TICKS;
        tickAutoPlayback(minecraft);
    }

    public static void openFocusScreen(InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || hand == null || hand == InteractionHand.OFF_HAND) {
            return;
        }
        ItemStack stack = minecraft.player.getItemInHand(hand);
        if (!(stack.getItem() instanceof PadItem)) {
            return;
        }
        minecraft.setScreen(new PadFocusScreen(hand));
    }

    public static PadDocument cachedDocumentFor(ItemStack stack) {
        UUID deviceId = PadItem.readDeviceId(stack);
        PadDocument cached = deviceId != null ? DOCUMENTS.get(deviceId) : null;
        PadDocument itemDocument = PadItem.readDocument(stack);
        if (cached == null) {
            return itemDocument;
        }
        PadDocument newer = compareVersion(itemDocument, cached) > 0 ? itemDocument : cached;
        return newer.copyWithLocked(itemDocument.locked());
    }

    public static boolean hasLockedIndexedPad(UUID deviceId) {
        return firstLocked(indexedStacks(deviceId)) != null;
    }

    public static void markDocumentDirty(ItemStack stack, PadDocument document) {
        UUID deviceId = PadItem.readDeviceId(stack);
        if (deviceId == null || document == null) {
            return;
        }
        DOCUMENTS.put(deviceId, document);
        PENDING_SYNCS.put(deviceId, new PendingDocumentSync(document, System.currentTimeMillis(), ++localSequence));
        syncRequested = true;
    }

    public static void receiveMirroredDocument(UUID deviceId, PadDocument document, long serverGameTime) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || deviceId == null || document == null) {
            return;
        }
        PadDocument current = DOCUMENTS.get(deviceId);
        if (current != null && compareVersion(document, current) < 0) {
            return;
        }
        DOCUMENTS.put(deviceId, document);
        PENDING_SYNCS.remove(deviceId);
        for (IndexedPadStack indexed : indexedStacks(deviceId)) {
            ItemStack stack = indexed.stack();
            boolean locked = PadItem.readLocked(stack);
            PadItem.writeDeviceId(stack, deviceId);
            PadItem.writeDocument(stack, document.copyWithLocked(locked));
        }
        rebuildPadIndex(minecraft);
    }

    public static void clearCachedDocuments() {
        DOCUMENTS.clear();
        PENDING_SYNCS.clear();
        PAD_INDEX.clear();
        fastSyncTicks = 0;
        autoPlayScanTicks = 0;
        padIndexRebuildTicks = 0;
        syncRequested = false;
    }

    private static void tickAutoPlayback(Minecraft minecraft) {
        // Pin 半径自动播放由服务端 PadDeviceHolderTracker 基于权威文档和玩家位置触发。
        // 客户端只负责文档同步/索引，避免与服务端同时发送 START 导致重复解析、播放卡住。
    }

    private static void sendPendingSyncs(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }
        if (PENDING_SYNCS.isEmpty()) {
            syncRequested = false;
            return;
        }
        for (Map.Entry<UUID, PendingDocumentSync> entry : new ArrayList<>(PENDING_SYNCS.entrySet())) {
            UUID deviceId = entry.getKey();
            PendingDocumentSync pending = PENDING_SYNCS.remove(deviceId);
            if (pending == null) {
                continue;
            }
            if (!PAD_INDEX.containsKey(deviceId)) {
                continue;
            }
            minecraft.getConnection().send(new PadStatePacket(deviceId, pending.document(), pending.updatedAtMillis(),
                    pending.sequence()));
        }
        syncRequested = !PENDING_SYNCS.isEmpty();
        fastSyncTicks = FAST_SYNC_INTERVAL_TICKS;
    }

    private static int compareVersion(PadDocument left, PadDocument right) {
        int timeCompare = Long.compare(left.updatedAtMillis(), right.updatedAtMillis());
        return timeCompare != 0 ? timeCompare : Long.compare(left.sequence(), right.sequence());
    }

    private static List<IndexedPadStack> indexedStacks(UUID deviceId) {
        return deviceId == null ? List.of() : PAD_INDEX.getOrDefault(deviceId, List.of());
    }

    private static IndexedPadStack firstLocked(List<IndexedPadStack> stacks) {
        if (stacks == null) {
            return null;
        }
        for (IndexedPadStack stack : stacks) {
            if (stack.locked()) {
                return stack;
            }
        }
        return null;
    }

    private static void rebuildPadIndex(Minecraft minecraft) {
        PAD_INDEX.clear();
        padIndexRebuildTicks = PAD_INDEX_REBUILD_INTERVAL_TICKS;
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        Set<ItemStack> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        addIndexedPad(
                minecraft.player.containerMenu != null ? minecraft.player.containerMenu.getCarried() : ItemStack.EMPTY,
                seen);
        addIndexedPad(minecraft.player.getMainHandItem(), seen);
        addIndexedPad(minecraft.player.getOffhandItem(), seen);
        for (int i = 0; i < minecraft.player.getInventory().getContainerSize(); i++) {
            addIndexedPad(minecraft.player.getInventory().getItem(i), seen);
        }
    }

    private static void addIndexedPad(ItemStack stack, Set<ItemStack> seen) {
        if (!PadItem.isPad(stack) || !seen.add(stack)) {
            return;
        }
        UUID deviceId = PadItem.readDeviceId(stack);
        if (deviceId == null) {
            return;
        }
        PAD_INDEX.computeIfAbsent(deviceId, ignored -> new ArrayList<>())
                .add(new IndexedPadStack(stack, PadItem.readLocked(stack)));
    }

    private record PendingDocumentSync(PadDocument document, long updatedAtMillis, long sequence) {
    }

    private record IndexedPadStack(ItemStack stack, boolean locked) {
    }
}