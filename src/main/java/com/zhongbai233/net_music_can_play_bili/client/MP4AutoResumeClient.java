package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/** 从客户端服务端配置镜像恢复 MP4 播放，绝不从 ItemStack 运行时 NBT 恢复。 */
public final class MP4AutoResumeClient {
    private static final int RESUME_DELAY_TICKS = 40;

    private static UUID lastPlayerId;
    private static int ticksSincePlayerReady;
    private static int lastResumeFingerprint;

    private MP4AutoResumeClient() {
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            ticksSincePlayerReady = 0;
            lastResumeFingerprint = 0;
            lastPlayerId = null;
            return;
        }

        UUID playerId = minecraft.player.getUUID();
        if (!playerId.equals(lastPlayerId)) {
            lastPlayerId = playerId;
            ticksSincePlayerReady = 0;
            lastResumeFingerprint = 0;
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
            lastResumeFingerprint = 0;
            return;
        }
        int queueSize = MP4Item.queueSize(stack);
        if (queueSize <= 0) {
            return;
        }
        int queueIndex = Math.max(0, Math.min(queueSize - 1, state.selectedQueueIndex()));
        long targetMillis = targetMillis(stack, state, queueIndex);
        UUID deviceId = MP4Item.getOrCreateDeviceId(stack);
        int fingerprint = resumeFingerprint(deviceId, queueIndex, state.volumePerMille(), targetMillis);
        if (MP4ClientPlayback.hasLocalPlayback(deviceId)) {
            return;
        }
        if (fingerprint == lastResumeFingerprint) {
            return;
        }

        lastResumeFingerprint = fingerprint;
        minecraft.getConnection().send(new MP4PlaybackControlPacket(
                MP4PlaybackControlPacket.Action.START,
                queueIndex,
                state.volumePerMille(),
            targetMillis,
            deviceId));
    }

    public static void reset() {
        ticksSincePlayerReady = 0;
        lastResumeFingerprint = 0;
        lastPlayerId = null;
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

    private static int resumeFingerprint(UUID playerId, int queueIndex, int volumePerMille, long targetMillis) {
        int result = playerId.hashCode();
        result = 31 * result + queueIndex;
        result = 31 * result + volumePerMille;
        result = 31 * result + Long.hashCode(targetMillis / 1000L);
        return result;
    }
}
