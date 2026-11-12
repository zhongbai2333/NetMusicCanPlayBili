package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 从客户端服务端配置镜像恢复 MP4 播放，绝不从 ItemStack 运行时 NBT 恢复。 */
public final class MP4AutoResumeClient {
    private static final int RESUME_DELAY_TICKS = 40;

    private static UUID lastPlayerId;
    private static int ticksSincePlayerReady;
    private static WeakReference<Object> lastConnection = new WeakReference<>(null);
    // A missing local decoder is not a reconnect. Each device gets one restoration
    // attempt per connection epoch; accepted playback and terminal events consume it.
    private static final Set<UUID> consumedDevices = new HashSet<>();

    private MP4AutoResumeClient() {
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!observeConnection(minecraft)) {
            return;
        }
        if (ticksSincePlayerReady < RESUME_DELAY_TICKS) {
            ticksSincePlayerReady++;
            return;
        }

        ItemStack stack = MP4Item.findAnyInInventory(minecraft.player);
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        MP4Item.State state = MP4Client.cachedStateFor(stack);
        if (!state.playing()) {
            return;
        }
        int queueSize = MP4Item.queueSize(stack);
        if (queueSize <= 0) {
            return;
        }
        int queueIndex = Math.max(0, Math.min(queueSize - 1, state.selectedQueueIndex()));
        long targetMillis = targetMillis(stack, state, queueIndex);
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId == null) {
            return;
        }
        if (ClientMediaPlayback.hasPlayback(deviceId)) {
            consumedDevices.add(deviceId);
            return;
        }
        if (!consumedDevices.add(deviceId)) {
            return;
        }
        minecraft.getConnection().send(new MP4PlaybackControlPacket(
                MP4PlaybackControlPacket.Action.START,
                queueIndex,
                state.volumePerMille(),
                targetMillis,
                deviceId));
    }

    public static void reset() {
        ticksSincePlayerReady = 0;
        // World unload and player replacement also use this cleanup hook. A
        // temporary loss of world/player is not proof of a new connection.
        // Keep only UUIDs and a weak listener identity until the next connection.
    }

    private static long targetMillis(ItemStack stack, MP4Item.State state, int queueIndex) {
        List<ItemStack> queue = MP4Item.readQueue(stack);
        if (queueIndex < 0 || queueIndex >= queue.size()) {
            return 0L;
        }
        ItemStack queueStack = queue.get(queueIndex);
        @SuppressWarnings("null")
        var songInfo = com.github.tartaricacid.netmusic.item.ItemMusicCD.getSongInfo(queueStack);
        int durationSeconds = songInfo != null ? Math.max(0, songInfo.songTime) : 0;
        if (durationSeconds <= 0) {
            return 0L;
        }
        long durationMillis = durationSeconds * 1000L;
        return Math.max(0L, Math.min(durationMillis - 50L,
                Math.round(state.progressPerMille() / 1000.0D * durationMillis)));
    }

    /** Called only after a sync is accepted, or a source is stopped/completed. */
    static void consumeRecovery(UUID deviceId) {
        if (deviceId != null && observeConnection(Minecraft.getInstance())) {
            consumedDevices.add(deviceId);
        }
    }

    private static boolean observeConnection(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) {
            ticksSincePlayerReady = 0;
            return false;
        }
        UUID playerId = minecraft.player.getUUID();
        Object connection = minecraft.getConnection();
        if (!playerId.equals(lastPlayerId) || connection != lastConnection.get()) {
            ticksSincePlayerReady = 0;
            consumedDevices.clear();
            lastPlayerId = playerId;
            lastConnection = new WeakReference<>(connection);
        }
        if (minecraft.level == null) {
            ticksSincePlayerReady = 0;
            return false;
        }
        return true;
    }
}
