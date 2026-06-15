package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.gui.MP4FocusScreen;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.network.MP4EnsureDeviceIdPacket;
import com.zhongbai233.net_music_can_play_bili.network.MP4StatePacket;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MP4Client {
    private static final int FAST_SYNC_INTERVAL_TICKS = 10;
    private static final int FULL_SYNC_INTERVAL_TICKS = 300;
    private static final Map<UUID, MP4Item.State> DEVICE_STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> DEVICE_STATE_TIMES = new ConcurrentHashMap<>();
    private static final Map<UUID, List<ItemStack>> DEVICE_QUEUES = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> DEVICE_HEADPHONE_LINKS = new ConcurrentHashMap<>();
    private static int ensureCooldownTicks;
    private static int fastSyncTicks;
    private static int fullSyncTicks;
    private static boolean focusedStateSyncRequested;

    private MP4Client() {
    }

    public static void tickHeldDeviceIdPrefetch() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            ensureCooldownTicks = 0;
            fastSyncTicks = 0;
            fullSyncTicks = 0;
            focusedStateSyncRequested = false;
            return;
        }
        pruneCachedStates(minecraft);
        tickFocusedStateSync(minecraft);
        if (ensureCooldownTicks > 0) {
            ensureCooldownTicks--;
            return;
        }
        boolean requested = ensureHeldDeviceId(minecraft, InteractionHand.MAIN_HAND);
        requested |= ensureHeldDeviceId(minecraft, InteractionHand.OFF_HAND);
        ensureCooldownTicks = requested ? 10 : 20;
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
        DEVICE_STATES.put(deviceId, state);
        DEVICE_STATE_TIMES.put(deviceId, safeTime);
        DEVICE_QUEUES.put(deviceId, cleanQueue(queue));
        DEVICE_HEADPHONE_LINKS.put(deviceId, headphoneLinked);
        return true;
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
        ensureCooldownTicks = 0;
        fastSyncTicks = 0;
        fullSyncTicks = 0;
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
        }
    }

    private static void tickFocusedStateSync(Minecraft minecraft) {
        if (MP4FocusState.active()) {
            if (fastSyncTicks > 0) {
                fastSyncTicks--;
            }
            if (fullSyncTicks > 0) {
                fullSyncTicks--;
            }
            if ((focusedStateSyncRequested && fastSyncTicks <= 0) || fullSyncTicks <= 0) {
                sendFocusedStateToServer(minecraft, fullSyncTicks <= 0);
            }
        } else {
            focusedStateSyncRequested = false;
            fastSyncTicks = 0;
            fullSyncTicks = FULL_SYNC_INTERVAL_TICKS;
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
                fullSyncTicks = FULL_SYNC_INTERVAL_TICKS;
                return;
            }
            DEVICE_STATES.put(deviceId, state);
            DEVICE_STATE_TIMES.putIfAbsent(deviceId, 0L);
            DEVICE_QUEUES.putIfAbsent(deviceId, MP4Item.readQueue(stack));
            minecraft.getConnection().send(MP4StatePacket.fromState(state, deviceId));
            focusedStateSyncRequested = false;
            fastSyncTicks = FAST_SYNC_INTERVAL_TICKS;
            fullSyncTicks = FULL_SYNC_INTERVAL_TICKS;
            if (state.playing()) {
                minecraft.getConnection().send(new MP4PlaybackControlPacket(
                        MP4PlaybackControlPacket.Action.VOLUME,
                        state.selectedQueueIndex(),
                        state.volumePerMille(),
                        0L,
                        deviceId));
            }
        }
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
    }

    private static void addDeviceId(java.util.Set<UUID> deviceIds, ItemStack stack) {
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId != null) {
            deviceIds.add(deviceId);
        }
    }
}
