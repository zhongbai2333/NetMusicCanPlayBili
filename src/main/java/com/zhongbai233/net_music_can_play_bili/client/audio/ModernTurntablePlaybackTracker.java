package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.bili.AudioUtils;
import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;

public final class ModernTurntablePlaybackTracker {
    private static final long STOP_GRACE_MILLIS = 1_500L;
    private static final ConcurrentHashMap<BlockPos, ActiveSession> ACTIVE = new ConcurrentHashMap<>();

    private ModernTurntablePlaybackTracker() {
    }

    public static boolean tryStart(BlockPos pos, String sessionId, int remainingSeconds) {
        if (pos == null || sessionId == null || sessionId.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        long expiresAt = now + Math.max(1, remainingSeconds) * 1000L + STOP_GRACE_MILLIS;
        ActiveSession next = new ActiveSession(sessionId, expiresAt);
        ActiveSession previous = ACTIVE.putIfAbsent(AudioUtils.copyPos(pos), next);
        if (previous == null) {
            return true;
        }
        if (previous.sessionId().equals(sessionId) && previous.expiresAtMillis() > now) {
            return false;
        }
        ACTIVE.put(AudioUtils.copyPos(pos), next);
        return true;
    }

    public static void finish(BlockPos pos, String sessionId) {
        if (pos == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        ACTIVE.computeIfPresent(pos, (ignored, active) -> active.sessionId().equals(sessionId) ? null : active);
    }

    public static boolean isCurrent(BlockPos pos, String sessionId) {
        if (pos == null || sessionId == null || sessionId.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        ActiveSession active = ACTIVE.get(pos);
        return active == null || active.sessionId().equals(sessionId);
    }

    private static void cleanup(long now) {
        ACTIVE.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    private record ActiveSession(String sessionId, long expiresAtMillis) {
    }
}
