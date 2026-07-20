package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
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
        ModernTurntableBlockEntity turntable = turntable(sourcePos);
        return turntable != null ? turntable.getVolume() : 1.0F;
    }

    static long targetRelativeTicks(BlockPos sourcePos, String sessionId, long startOffsetTicks) {
        if (ClientMinecartAudioAnchors.isMoving(sessionId)) {
            return Long.MAX_VALUE;
        }
        ModernTurntableBlockEntity turntable = turntable(sourcePos);
        Minecraft minecraft = Minecraft.getInstance();
        if (turntable == null || minecraft == null || minecraft.level == null || !turntable.isPlaying()) {
            return Long.MAX_VALUE;
        }
        long targetTicks = turntable.getPlaybackElapsedMillis(minecraft.level.getGameTime()) / 50L;
        return Math.max(0L, targetTicks - startOffsetTicks + AUDIO_SYNC_AHEAD_TOLERANCE_TICKS);
    }

    private static ModernTurntableBlockEntity turntable(BlockPos sourcePos) {
        if (!isWorldPosition(sourcePos)) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return null;
        }
        return minecraft.level.getBlockEntity(sourcePos) instanceof ModernTurntableBlockEntity turntable
                ? turntable
                : null;
    }

    private static boolean isWorldPosition(BlockPos pos) {
        return pos != null && pos.getX() > Integer.MIN_VALUE + 2;
    }
}