package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.compat.minecartrevolution.MinecartTurntableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Pure playback-session identity and tick/millisecond calculations for a modern turntable. */
final class ModernTurntablePlaybackClock {
    private ModernTurntablePlaybackClock() {
    }

    static String sessionId(Level level, BlockPos sourcePos, long startedGameTime, int seekGeneration) {
        var hostUuid = MinecartTurntableCompat.hostUuid(level);
        String sourceId = hostUuid != null ? "minecart-" + hostUuid : Long.toString(sourcePos.asLong());
        return sourceId + "-" + startedGameTime + (seekGeneration > 0 ? "-" + seekGeneration : "");
    }

    static int remainingSeconds(boolean playing, int durationSeconds, long startedGameTime, long gameTime) {
        return !playing || durationSeconds <= 0
                ? 0
                : Math.max(0, durationSeconds - elapsedSeconds(durationSeconds, startedGameTime, gameTime));
    }

    static long elapsedMillis(boolean playing, int durationSeconds, long startedGameTime, long gameTime) {
        return !playing
                ? 0L
                : Math.min(durationSeconds * 1000L, Math.max(0L, (gameTime - startedGameTime) * 50L));
    }

    private static int elapsedSeconds(int durationSeconds, long startedGameTime, long gameTime) {
        long elapsed = Math.max(0L, (gameTime - startedGameTime) / 20L);
        return Math.min(durationSeconds, (int) Math.min(Integer.MAX_VALUE, elapsed));
    }
}
