package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.bili.AudioUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;

public final class ModernTurntablePlaybackTracker {
    private static final long STOP_GRACE_MILLIS = 5_000L;
    private static final long DUPLICATE_SUPPRESS_MILLIS = 1_500L;
    private static final ConcurrentHashMap<BlockPos, ActiveSession> ACTIVE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ModernTurntableSound, Boolean> ACTIVE_SOUNDS = new ConcurrentHashMap<>();

    private ModernTurntablePlaybackTracker() {
    }

    public static boolean tryStart(BlockPos pos, String sessionId, int remainingSeconds) {
        if (pos == null || sessionId == null || sessionId.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        long expiresAt = now + Math.max(1, remainingSeconds) * 1000L + STOP_GRACE_MILLIS;
        BlockPos key = AudioUtils.copyPos(pos);
        ActiveSession previous = ACTIVE.get(key);
        if (previous != null && previous.sessionId().equals(sessionId)) {
            if (previous.expiresAtMillis() > now) {
                return false;
            }
            if (previous.suppressUntilMillis() > now) {
                return false;
            }
        }
        ACTIVE.put(key, new ActiveSession(sessionId, expiresAt, now + DUPLICATE_SUPPRESS_MILLIS, false));
        return true;
    }

    public static void markStreamStarted(BlockPos pos, String sessionId) {
        if (pos == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        ACTIVE.computeIfPresent(pos, (ignored, active) -> active.sessionId().equals(sessionId)
                ? new ActiveSession(active.sessionId(), active.expiresAtMillis(), active.suppressUntilMillis(), true)
                : active);
    }

    public static void registerSound(ModernTurntableSound sound) {
        if (sound != null) {
            ACTIVE_SOUNDS.put(sound, Boolean.TRUE);
        }
    }

    public static void unregisterSound(ModernTurntableSound sound) {
        if (sound != null) {
            ACTIVE_SOUNDS.remove(sound);
        }
    }

    public static void stopAllSounds() {
        Minecraft minecraft = Minecraft.getInstance();
        for (ModernTurntableSound sound : ACTIVE_SOUNDS.keySet()) {
            sound.stopFromTracker();
            if (minecraft != null) {
                minecraft.getSoundManager().stop(sound);
            }
        }
        ACTIVE_SOUNDS.clear();
        clear();
    }

    public static void finish(BlockPos pos, String sessionId) {
        if (pos == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        ACTIVE.computeIfPresent(pos, (ignored, active) -> active.sessionId().equals(sessionId) ? null : active);
    }

    /** 客户端切世界/断连时清空全部跟踪记录，避免旧 session 的 streamStarted 标记阻断重连后的同步 */
    public static void clear() {
        ACTIVE.clear();
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

    private record ActiveSession(String sessionId, long expiresAtMillis, long suppressUntilMillis,
            boolean streamStarted) {
    }
}
