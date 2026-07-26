package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.blockentity.PlaybackAudioSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** 输出设备之外的世界状态、会话有效性和 pacing 策略。 */
final class ClientAudioOutputPolicy {
    private static final long AUDIO_SYNC_AHEAD_TOLERANCE_TICKS = Long.getLong(
            "bili.audio.openal.ahead_tolerance_ticks", 0L);

    private ClientAudioOutputPolicy() {
    }

    static boolean isCurrentSession(BlockPos sourcePos, String sessionId) {
        if (!isWorldPosition(sourcePos) || sessionId == null || sessionId.isBlank()) {
            return true;
        }
        String current = ModernTurntablePlaybackTracker.currentSessionId(sourcePos, sessionId);
        return current.isBlank() || current.equals(sessionId);
    }

    static float volume(BlockPos sourcePos) {
        PlaybackAudioSource source = source(sourcePos);
        return source != null ? source.getVolume() : 1.0F;
    }

    static long targetRelativeTicks(BlockPos sourcePos, String sessionId, long startOffsetTicks) {
        if (ClientMinecartAudioAnchors.isMoving(sessionId)) {
            return Long.MAX_VALUE;
        }
        PlaybackAudioSource source = source(sourcePos);
        Minecraft minecraft = Minecraft.getInstance();
        if (source == null || minecraft == null || minecraft.level == null || !source.isPlaying()) {
            return Long.MAX_VALUE;
        }
        long elapsedMillis = source.getPlaybackElapsedMillis(minecraft.level.getGameTime());
        if (elapsedMillis < 0L) {
            // 无界媒体（直播）没有服务端进度，不做 pacing
            return Long.MAX_VALUE;
        }
        long targetTicks = elapsedMillis / 50L;
        return Math.max(0L, targetTicks - startOffsetTicks + AUDIO_SYNC_AHEAD_TOLERANCE_TICKS);
    }

    private static PlaybackAudioSource source(BlockPos sourcePos) {
        if (!isWorldPosition(sourcePos)) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return null;
        }
        return minecraft.level.getBlockEntity(sourcePos) instanceof PlaybackAudioSource source
                ? source
                : null;
    }

    private static boolean isWorldPosition(BlockPos pos) {
        return pos != null && pos.getX() > Integer.MIN_VALUE + 2;
    }
}
