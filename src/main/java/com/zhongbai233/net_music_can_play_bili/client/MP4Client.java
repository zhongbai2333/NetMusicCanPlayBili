package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.gui.MP4FocusScreen;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.network.MP4EnsureDeviceIdPacket;
import com.zhongbai233.net_music_can_play_bili.network.MP4EnsureInventoryDeviceIdPacket;
import com.zhongbai233.net_music_can_play_bili.network.MP4StatePacket;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public final class MP4Client {
    private static final int FAST_SYNC_INTERVAL_TICKS = 10;
    private static final long PENDING_SELECTION_CONFIRM_MILLIS = 1500L;
    private static final Map<UUID, MP4Item.State> DEVICE_STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> DEVICE_STATE_TIMES = new ConcurrentHashMap<>();
    private static final Map<UUID, List<ItemStack>> DEVICE_QUEUES = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> DEVICE_HEADPHONE_LINKS = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingSelection> PENDING_SELECTIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingStateSync> PENDING_STATE_SYNCS = new ConcurrentHashMap<>();
    private static long localStateSequence;
    private static int ensureCooldownTicks;
    private static int fastSyncTicks;
    private static boolean focusedStateSyncRequested;

    private MP4Client() {
    }

    public static void tickHeldDeviceIdPrefetch() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            ensureCooldownTicks = 0;
            fastSyncTicks = 0;
            focusedStateSyncRequested = false;
            return;
        }
        pruneCachedStates(minecraft);
        tickFocusedStateSync(minecraft);
        if (ensureCooldownTicks > 0) {
            ensureCooldownTicks--;
            return;
        }
        boolean requested = ensureHotbarDeviceIds(minecraft);
        requested |= ensureHeldDeviceId(minecraft, InteractionHand.OFF_HAND);
        ensureCooldownTicks = requested ? 10 : 20;
    }

    private static boolean ensureHotbarDeviceIds(Minecraft minecraft) {
        int hotbarSlots = Math.min(9, minecraft.player.getInventory().getContainerSize());
        boolean requested = false;
        for (int slot = 0; slot < hotbarSlots; slot++) {
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof MP4Item)) {
                continue;
            }
            UUID deviceId = MP4Item.readDeviceId(stack);
            if (deviceId != null && DEVICE_STATES.containsKey(deviceId)) {
                continue;
            }
            minecraft.getConnection().send(new MP4EnsureInventoryDeviceIdPacket(slot));
            requested = true;
        }
        return requested;
    }

    private static boolean ensureHeldDeviceId(Minecraft minecraft, InteractionHand hand) {
        ItemStack stack = minecraft.player.getItemInHand(hand);
        if (!(stack.getItem() instanceof MP4Item)) {
            return false;
        }
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId != null && DEVICE_STATES.containsKey(deviceId)) {
            return false;
        }
        minecraft.getConnection().send(new MP4EnsureDeviceIdPacket(hand));
        return true;
    }

    public static void openFocusScreen(InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || hand == InteractionHand.OFF_HAND) {
            return;
        }
        ItemStack stack = minecraft.player.getItemInHand(hand);
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        MP4FocusState.load(stateForHeldRender(stack));
        MP4FocusState.loadQueue(queueForHeldRender(stack));
        boolean hintJustShown = MP4FocusState.showRotationHintIfNotShown();
        minecraft.setScreen(new MP4FocusScreen(hand));
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().send(new MP4EnsureDeviceIdPacket(hand));
            if (hintJustShown) {
                syncFocusedStateToServer();
            }
        }
    }

    public static void receiveDeviceId(InteractionHand hand, UUID deviceId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || deviceId == null) {
            return;
        }
        ItemStack stack = minecraft.player.getItemInHand(hand);
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        MP4Item.writeDeviceId(stack, deviceId);
        if (MP4FocusState.activeFor(hand) && focusedStateSyncRequested) {
            fastSyncTicks = 0;
        }
    }

    public static void receiveContainerDeviceId(int containerSlotIndex, UUID deviceId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || deviceId == null || minecraft.player.containerMenu == null) {
            return;
        }
        if (containerSlotIndex < 0 || containerSlotIndex >= minecraft.player.containerMenu.slots.size()) {
            return;
        }
        ItemStack stack = minecraft.player.containerMenu.slots.get(containerSlotIndex).getItem();
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        MP4Item.writeDeviceId(stack, deviceId);
    }

    public static void receiveInventoryDeviceId(int inventorySlot, UUID deviceId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || deviceId == null) {
            return;
        }
        if (inventorySlot < 0 || inventorySlot >= Math.min(9, minecraft.player.getInventory().getContainerSize())) {
            return;
        }
        ItemStack stack = minecraft.player.getInventory().getItem(inventorySlot);
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        MP4Item.writeDeviceId(stack, deviceId);
    }

    public static void receiveOpenState(InteractionHand hand, UUID deviceId, MP4Item.State state, long updatedGameTime,
            boolean headphoneLinked, List<ItemStack> queue) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || deviceId == null || state == null) {
            return;
        }
        ItemStack stack = minecraft.player.getItemInHand(hand);
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        MP4Item.writeDeviceId(stack, deviceId);
        acceptServerConfig(deviceId, state, updatedGameTime, headphoneLinked, queue);
        if (MP4FocusState.activeFor(hand) && focusedStateSyncRequested) {
            fastSyncTicks = 0;
        }
    }

    public static void receiveMirroredState(UUID deviceId, MP4Item.State state, long updatedGameTime,
            boolean headphoneLinked, List<ItemStack> queue) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || deviceId == null || state == null) {
            return;
        }
        acceptServerConfig(deviceId, state, updatedGameTime, headphoneLinked, queue);
    }

    private static boolean acceptServerConfig(UUID deviceId, MP4Item.State state, long updatedGameTime,
            boolean headphoneLinked, List<ItemStack> queue) {
        long safeTime = Math.max(0L, updatedGameTime);
        long currentTime = DEVICE_STATE_TIMES.getOrDefault(deviceId, -1L);
        if (safeTime < currentTime) {
            return false;
        }
        state = preservePendingSelection(deviceId, state, queue);
        DEVICE_STATES.put(deviceId, state);
        DEVICE_STATE_TIMES.put(deviceId, safeTime);
        DEVICE_QUEUES.put(deviceId, cleanQueue(queue));
        DEVICE_HEADPHONE_LINKS.put(deviceId, headphoneLinked);
        return true;
    }

    public static void markStackStateDirty(ItemStack stack, MP4Item.State state) {
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId == null || state == null) {
            return;
        }
        cacheLocalState(deviceId, state, stack);
        PENDING_STATE_SYNCS.put(deviceId,
                new PendingStateSync(state, System.currentTimeMillis(), ++localStateSequence));
        focusedStateSyncRequested = true;
    }

    private static MP4Item.State preservePendingSelection(UUID deviceId, MP4Item.State state, List<ItemStack> queue) {
        PendingSelection pending = PENDING_SELECTIONS.get(deviceId);
        if (pending == null) {
            return state;
        }
        if (System.currentTimeMillis() - pending.createdAtMillis() > PENDING_SELECTION_CONFIRM_MILLIS) {
            PENDING_SELECTIONS.remove(deviceId);
            return state;
        }
        int queueSize = queue == null || queue.isEmpty() ? Math.max(1, state.selectedQueueIndex() + 1) : queue.size();
        int selected = Math.max(0, Math.min(queueSize - 1, pending.selectedQueueIndex()));
        if (state.selectedQueueIndex() == selected) {
            PENDING_SELECTIONS.remove(deviceId);
            return state;
        }
        return new MP4Item.State(state.playing(), state.shuffle(), state.videoEnabled(), state.landscape(),
                state.qualityIndex(), selected, state.queueScrollOffset(), state.volumePerMille(), state.repeatMode(),
                state.playlistOpen(), state.lyricsEnabled(), state.subtitleMode(), state.subtitleAiEnabled(), 0,
                state.rotationHintShown());
    }

    private static List<ItemStack> cleanQueue(List<ItemStack> queue) {
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }
        return queue.stream()
                .filter(MP4Item::isNetMusicDisc)
                .limit(MP4Item.MAX_QUEUE_SIZE)
                .map(stack -> stack.copyWithCount(1))
                .toList();
    }

    public static MP4Item.State stateForHeldRender(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof MP4Item)) {
            return MP4Item.State.DEFAULT;
        }
        UUID deviceId = MP4Item.readDeviceId(stack);
        MP4Item.State baseState = deviceId != null ? DEVICE_STATES.get(deviceId) : null;
        if (baseState == null) {
            baseState = MP4Item.State.DEFAULT;
        }
        return MP4ClientPlayback.overlayPlaybackState(deviceId, baseState);
    }

    public static MP4Item.State cachedStateFor(ItemStack stack) {
        UUID deviceId = MP4Item.readDeviceId(stack);
        MP4Item.State state = deviceId != null ? DEVICE_STATES.get(deviceId) : null;
        return state != null ? state : MP4Item.State.DEFAULT;
    }

    public static void selectQueueIndexLocally(ItemStack stack, int selectedQueueIndex) {
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId == null) {
            return;
        }
        MP4Item.State old = cachedStateFor(stack);
        int queueSize = Math.max(1, MP4Item.queueSize(stack));
        int selected = Math.max(0, Math.min(queueSize - 1, selectedQueueIndex));
        int progressPerMille = selected == old.selectedQueueIndex() ? old.progressPerMille() : 0;
        MP4Item.State state = new MP4Item.State(old.playing(), old.shuffle(), old.videoEnabled(),
                old.landscape(), old.qualityIndex(), selected, old.queueScrollOffset(), old.volumePerMille(),
                old.repeatMode(), old.playlistOpen(), old.lyricsEnabled(), old.subtitleMode(),
                old.subtitleAiEnabled(), progressPerMille, old.rotationHintShown());
        markStackStateDirty(stack, state);
        PENDING_SELECTIONS.put(deviceId, new PendingSelection(selected, System.currentTimeMillis()));
    }

    public static List<ItemStack> cachedQueueFor(ItemStack stack) {
        UUID deviceId = MP4Item.readDeviceId(stack);
        List<ItemStack> queue = deviceId != null ? DEVICE_QUEUES.get(deviceId) : null;
        return queue != null ? queue : MP4Item.readQueue(stack);
    }

    public static boolean headphoneLinked(UUID deviceId) {
        return deviceId != null && DEVICE_HEADPHONE_LINKS.getOrDefault(deviceId, false);
    }

    private static List<ItemStack> queueForHeldRender(ItemStack stack) {
        return cachedQueueFor(stack);
    }

    public static void cacheFocusedState(UUID deviceId) {
        if (deviceId != null) {
            DEVICE_STATES.put(deviceId, MP4FocusState.save());
            DEVICE_STATE_TIMES.putIfAbsent(deviceId, 0L);
            DEVICE_QUEUES.putIfAbsent(deviceId, MP4Item.readQueue(stackForActiveDevice()));
        }
    }

    private static ItemStack stackForActiveDevice() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && MP4FocusState.active()
                ? minecraft.player.getItemInHand(MP4FocusState.hand())
                : ItemStack.EMPTY;
    }

    public static void clearCachedStates() {
        DEVICE_STATES.clear();
        DEVICE_STATE_TIMES.clear();
        DEVICE_QUEUES.clear();
        DEVICE_HEADPHONE_LINKS.clear();
        PENDING_SELECTIONS.clear();
        PENDING_STATE_SYNCS.clear();
        ensureCooldownTicks = 0;
        fastSyncTicks = 0;
        focusedStateSyncRequested = false;
    }

    public static void syncFocusedStateToServer() {
        requestFocusedStateSync();
    }

    public static void updateFocusedLocalState() {
        requestFocusedStateSync();
    }

    public static void flushFocusedStateToServer() {
        Minecraft minecraft = Minecraft.getInstance();
        sendFocusedStateToServer(minecraft, true);
    }

    private static void requestFocusedStateSync() {
        cacheFocusedStateFromActiveStack();
        focusedStateSyncRequested = true;
    }

    private static void cacheFocusedStateFromActiveStack() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !MP4FocusState.active()) {
            return;
        }
        ItemStack stack = minecraft.player.getItemInHand(MP4FocusState.hand());
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId != null) {
            DEVICE_STATES.put(deviceId, MP4FocusState.save());
            DEVICE_STATE_TIMES.putIfAbsent(deviceId, 0L);
            DEVICE_QUEUES.putIfAbsent(deviceId, MP4Item.readQueue(stack));
            PENDING_STATE_SYNCS.put(deviceId,
                    new PendingStateSync(MP4FocusState.save(), System.currentTimeMillis(), ++localStateSequence));
        }
    }

    private static void tickFocusedStateSync(Minecraft minecraft) {
        if (fastSyncTicks > 0) {
            fastSyncTicks--;
        }
        if (focusedStateSyncRequested && fastSyncTicks <= 0) {
            sendPendingStateSyncs(minecraft);
        }
    }

    private static void sendFocusedStateToServer(Minecraft minecraft, boolean force) {
        if (!force && !focusedStateSyncRequested) {
            return;
        }
        if (minecraft == null) {
            return;
        }
        if (minecraft.player == null) {
            return;
        }
        ItemStack stack = minecraft.player.getItemInHand(MP4FocusState.hand());
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        MP4Item.State state = MP4FocusState.save();
        if (minecraft.getConnection() != null) {
            java.util.UUID deviceId = MP4Item.readDeviceId(stack);
            if (deviceId == null) {
                minecraft.getConnection().send(new MP4EnsureDeviceIdPacket(MP4FocusState.hand()));
                focusedStateSyncRequested = true;
                fastSyncTicks = FAST_SYNC_INTERVAL_TICKS;
                return;
            }
            markStackStateDirty(stack, state);
            if (force) {
                sendPendingStateSyncs(minecraft);
            }
        }
    }

    private static void sendPendingStateSyncs(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }
        if (PENDING_STATE_SYNCS.isEmpty()) {
            focusedStateSyncRequested = false;
            return;
        }
        for (Map.Entry<UUID, PendingStateSync> entry : new ArrayList<>(PENDING_STATE_SYNCS.entrySet())) {
            UUID deviceId = entry.getKey();
            PendingStateSync pending = PENDING_STATE_SYNCS.remove(deviceId);
            if (pending == null) {
                continue;
            }
            ItemStack stack = MP4Item.findByDeviceId(minecraft.player, deviceId);
            if (!(stack.getItem() instanceof MP4Item)) {
                continue;
            }
            cacheLocalState(deviceId, pending.state(), stack);
            minecraft.getConnection().send(MP4StatePacket.fromState(pending.state(), deviceId,
                    pending.updatedAtMillis(), pending.sequence()));
            if (pending.state().playing()) {
                minecraft.getConnection().send(new MP4PlaybackControlPacket(
                        MP4PlaybackControlPacket.Action.VOLUME,
                        pending.state().selectedQueueIndex(),
                        pending.state().volumePerMille(),
                        0L,
                        deviceId));
            }
        }
        focusedStateSyncRequested = !PENDING_STATE_SYNCS.isEmpty();
        fastSyncTicks = FAST_SYNC_INTERVAL_TICKS;
    }

    private static void pruneCachedStates(Minecraft minecraft) {
        if (minecraft.player == null || DEVICE_STATES.isEmpty()) {
            return;
        }
        java.util.HashSet<UUID> present = new java.util.HashSet<>();
        addDeviceId(present, minecraft.player.containerMenu != null
                ? minecraft.player.containerMenu.getCarried()
                : ItemStack.EMPTY);
        addDeviceId(present, minecraft.player.getMainHandItem());
        MP4DeviceStacks.addHotbarAndOffhandDeviceIds(minecraft.player, present);
        DEVICE_STATES.keySet().removeIf(deviceId -> !present.contains(deviceId));
        DEVICE_STATE_TIMES.keySet().removeIf(deviceId -> !present.contains(deviceId));
        DEVICE_QUEUES.keySet().removeIf(deviceId -> !present.contains(deviceId));
        DEVICE_HEADPHONE_LINKS.keySet().removeIf(deviceId -> !present.contains(deviceId));
        PENDING_SELECTIONS.keySet().removeIf(deviceId -> !present.contains(deviceId));
        PENDING_STATE_SYNCS.keySet().removeIf(deviceId -> !present.contains(deviceId));
    }

    private static void cacheLocalState(UUID deviceId, MP4Item.State state, ItemStack stack) {
        DEVICE_STATES.put(deviceId, state);
        DEVICE_STATE_TIMES.putIfAbsent(deviceId, 0L);
        DEVICE_QUEUES.putIfAbsent(deviceId, MP4Item.readQueue(stack));
    }

    private static void addDeviceId(java.util.Set<UUID> deviceIds, ItemStack stack) {
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId != null) {
            deviceIds.add(deviceId);
        }
    }

    private record PendingSelection(int selectedQueueIndex, long createdAtMillis) {
    }

    private record PendingStateSync(MP4Item.State state, long updatedAtMillis, long sequence) {
    }
}
