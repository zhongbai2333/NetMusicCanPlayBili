package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MP4 设备的服务端权威播放状态。
 * <p>
 * 播放/会话字段在这里拥有权威性，并镜像到 SavedData。队列有意作为服务端播放副本：
 * MP4 ItemStack 仍保留自身队列作为物品内容，以便原版提示和容器交互使用；本存储追踪同步副本用于播放和自动切歌。
 */
public final class MP4DeviceStateStore {
    private static final Map<UUID, DeviceEntry> RUNTIME = new ConcurrentHashMap<>();

    private MP4DeviceStateStore() {
    }

    public static DeviceEntry getOrCreate(ServerLevel level, UUID deviceId, ItemStack stack) {
        if (deviceId == null) {
            return DeviceEntry.EMPTY;
        }
        DeviceEntry runtime = RUNTIME.get(deviceId);
        if (runtime != null) {
            return runtime;
        }
        DeviceEntry saved = level != null ? MP4PlaybackSavedData.get(level).device(deviceId).orElse(null) : null;
        if (saved != null) {
            DeviceEntry restored = withStackQueue(saved, stack).normalized();
            RUNTIME.put(deviceId, restored);
            return restored;
        }
        DeviceEntry created = fromStackContents(stack).normalized();
        RUNTIME.put(deviceId, created);
        return created;
    }

    public static DeviceEntry get(UUID deviceId) {
        return deviceId == null ? DeviceEntry.EMPTY : RUNTIME.getOrDefault(deviceId, DeviceEntry.EMPTY);
    }

    public static void update(ServerLevel level, UUID deviceId, DeviceEntry entry) {
        if (deviceId == null || entry == null) {
            return;
        }
        DeviceEntry normalized = entry.withUpdatedGameTime(nextUpdatedGameTime(level, deviceId, entry)).normalized();
        RUNTIME.put(deviceId, normalized);
        if (level != null) {
            MP4PlaybackSavedData.get(level).putDevice(deviceId, normalized);
        }
        MP4DeviceHolderTracker.invalidate(deviceId);
    }

    private static long nextUpdatedGameTime(ServerLevel level, UUID deviceId, DeviceEntry entry) {
        long base = Math.max(0L, entry.updatedGameTime());
        if (level != null) {
            base = Math.max(base, level.getGameTime());
        }
        DeviceEntry current = RUNTIME.get(deviceId);
        if (current != null) {
            base = Math.max(base, current.updatedGameTime() + 1L);
        }
        return base;
    }

    public static void updateState(ServerLevel level, UUID deviceId, MP4Item.State state) {
        DeviceEntry current = getOrCreate(level, deviceId, ItemStack.EMPTY);
        update(level, deviceId, current.withState(state));
    }

    public static void updateQueue(ServerLevel level, UUID deviceId, List<ItemStack> queue) {
        DeviceEntry current = getOrCreate(level, deviceId, ItemStack.EMPTY);
        update(level, deviceId, current.withQueue(queue));
    }

    public static void syncQueueCopy(ServerLevel level, UUID deviceId, ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof MP4Item)) {
            return;
        }
        List<ItemStack> queue = MP4Item.readQueue(stack);
        DeviceEntry current = getOrCreate(level, deviceId, stack);
        if (!sameQueue(current.queue(), queue)) {
            updateQueue(level, deviceId, queue);
        }
    }

    private static boolean sameQueue(List<ItemStack> left, List<ItemStack> right) {
        List<ItemStack> safeLeft = left == null ? List.of() : left;
        List<ItemStack> safeRight = right == null ? List.of() : right;
        if (safeLeft.size() != safeRight.size()) {
            return false;
        }
        for (int i = 0; i < safeLeft.size(); i++) {
            ItemStack leftStack = safeLeft.get(i);
            ItemStack rightStack = safeRight.get(i);
            if (leftStack.getItem() != rightStack.getItem()
                    || !leftStack.getComponents().equals(rightStack.getComponents())) {
                return false;
            }
        }
        return true;
    }

    public static void recordPlayback(ServerLevel level, UUID deviceId, int queueIndex, long elapsedMillis,
            int durationSeconds, int volumePerMille, String sessionId, boolean playing) {
        DeviceEntry current = getOrCreate(level, deviceId, ItemStack.EMPTY);
        MP4Item.State old = current.state();
        int progress = progressPerMille(elapsedMillis, durationSeconds, old.progressPerMille());
        MP4Item.State state = new MP4Item.State(playing, old.shuffle(), old.videoEnabled(), old.landscape(),
                old.qualityIndex(), queueIndex, old.queueScrollOffset(), clamp(volumePerMille, 0, 1000),
                old.repeatMode(), old.playlistOpen(), old.lyricsEnabled(), old.subtitleMode(),
                old.subtitleAiEnabled(), progress, old.rotationHintShown());
        update(level, deviceId, new DeviceEntry(state, current.queue(), Math.max(0L, elapsedMillis),
                Math.max(0, durationSeconds), sessionId == null ? "" : sessionId));
    }

    public static void flush(ServerLevel level) {
        if (level == null || RUNTIME.isEmpty()) {
            return;
        }
        MP4PlaybackSavedData data = MP4PlaybackSavedData.get(level);
        RUNTIME.forEach(data::putDevice);
    }

    private static DeviceEntry fromStackContents(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof MP4Item)) {
            return DeviceEntry.EMPTY;
        }
        return new DeviceEntry(MP4Item.State.DEFAULT, MP4Item.readQueue(stack), 0L, 0, "", 0L);
    }

    private static DeviceEntry withStackQueue(DeviceEntry entry, ItemStack stack) {
        if (entry == null || stack.isEmpty() || !(stack.getItem() instanceof MP4Item)) {
            return entry == null ? DeviceEntry.EMPTY : entry;
        }
        List<ItemStack> queue = MP4Item.readQueue(stack);
        if (queue.isEmpty()) {
            return entry;
        }
        return new DeviceEntry(entry.state(), queue, entry.elapsedMillis(), entry.durationSeconds(),
                entry.sessionId(), entry.updatedGameTime());
    }

    private static int progressPerMille(long elapsedMillis, int durationSeconds, int fallback) {
        if (durationSeconds <= 0) {
            return clamp(fallback, 0, 1000);
        }
        long durationMillis = durationSeconds * 1000L;
        long elapsed = Math.max(0L, Math.min(durationMillis, elapsedMillis));
        return clamp((int) Math.round(elapsed * 1000.0D / durationMillis), 0, 1000);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record DeviceEntry(MP4Item.State state, List<ItemStack> queue, long elapsedMillis, int durationSeconds,
            String sessionId, long updatedGameTime) {
        public static final DeviceEntry EMPTY = new DeviceEntry(MP4Item.State.DEFAULT, List.of(), 0L, 0, "", 0L);

        public DeviceEntry(MP4Item.State state, List<ItemStack> queue, long elapsedMillis, int durationSeconds,
                String sessionId) {
            this(state, queue, elapsedMillis, durationSeconds, sessionId, 0L);
        }

        public DeviceEntry normalized() {
            List<ItemStack> cleanQueue = queue == null ? List.of()
                    : queue.stream()
                            .filter(MP4Item::isNetMusicDisc)
                            .limit(MP4Item.MAX_QUEUE_SIZE)
                            .map(stack -> stack.copyWithCount(1))
                            .toList();
            MP4Item.State safeState = state == null ? MP4Item.State.DEFAULT : state;
            int maxIndex = Math.max(0, cleanQueue.size() - 1);
            MP4Item.State normalizedState = new MP4Item.State(
                    safeState.playing(),
                    safeState.shuffle(),
                    safeState.videoEnabled(),
                    safeState.landscape(),
                    clamp(safeState.qualityIndex(), 0, MP4Item.State.MAX_QUALITY_INDEX),
                    clamp(safeState.selectedQueueIndex(), 0, maxIndex),
                    clamp(safeState.queueScrollOffset(), 0, MP4Item.State.MAX_QUEUE_SCROLL_OFFSET),
                    clamp(safeState.volumePerMille(), 0, 1000),
                    clamp(safeState.repeatMode(), 0, 2),
                    safeState.playlistOpen(),
                    safeState.lyricsEnabled(),
                    clamp(safeState.subtitleMode(), 0, 1),
                    safeState.subtitleAiEnabled(),
                    clamp(safeState.progressPerMille(), 0, 1000),
                    safeState.rotationHintShown());
            int duration = Math.max(0, durationSeconds);
            long maxElapsed = duration > 0 ? Math.max(0L, duration * 1000L - 50L) : Long.MAX_VALUE;
            return new DeviceEntry(normalizedState, cleanQueue, Math.max(0L, Math.min(maxElapsed, elapsedMillis)),
                    duration, sessionId == null ? "" : sessionId, Math.max(0L, updatedGameTime));
        }

        public DeviceEntry withState(MP4Item.State newState) {
            return new DeviceEntry(newState, queue, elapsedMillis, durationSeconds, sessionId, updatedGameTime)
                    .normalized();
        }

        public DeviceEntry withQueue(List<ItemStack> newQueue) {
            return new DeviceEntry(state, newQueue, elapsedMillis, durationSeconds, sessionId, updatedGameTime)
                    .normalized();
        }

        public DeviceEntry withUpdatedGameTime(long newUpdatedGameTime) {
            return new DeviceEntry(state, queue, elapsedMillis, durationSeconds, sessionId, newUpdatedGameTime)
                    .normalized();
        }

        public int durationSecondsForSelected() {
            int index = state.selectedQueueIndex();
            if (index < 0 || index >= queue.size()) {
                return 0;
            }
            @SuppressWarnings("null")
            ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(queue.get(index));
            return info != null ? Math.max(0, info.songTime) : 0;
        }
    }
}
