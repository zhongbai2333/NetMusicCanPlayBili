package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.compat.minecartrevolution.MinecartTurntableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Pure playback-session identity for a modern turntable. */
final class ModernTurntablePlaybackClock {
    private ModernTurntablePlaybackClock() {
    }

    static String sessionId(Level level, BlockPos sourcePos, long startedGameTime, int seekGeneration) {
        var hostUuid = MinecartTurntableCompat.hostUuid(level);
        String sourceId = hostUuid != null ? "minecart-" + hostUuid : Long.toString(sourcePos.asLong());
        return sourceId + "-" + startedGameTime + (seekGeneration > 0 ? "-" + seekGeneration : "");
    }

}
