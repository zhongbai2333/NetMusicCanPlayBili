package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public final class ModernTurntablePlaybackTracker {
    private static final long STOP_GRACE_MILLIS = 5_000L;
    private static final long DUPLICATE_SUPPRESS_MILLIS = 1_500L;
    private static final ConcurrentHashMap<Object, ActiveSession> ACTIVE = new ConcurrentHashMap<>();
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
        Object key = keyFor(pos, sessionId);
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
        ACTIVE.computeIfPresent(keyFor(pos, sessionId), (ignored, active) -> active.sessionId().equals(sessionId)
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
        ACTIVE.computeIfPresent(keyFor(pos, sessionId),
            (ignored, active) -> active.sessionId().equals(sessionId) ? null : active);
    }

    /** 客户端切世界/断连时清空全部跟踪记录，避免旧 session 的 streamStarted 标记阻断重连后的同步 */
    public static void clear() {
        ACTIVE.clear();
        ClientMinecartAudioAnchors.clear();
    }

    public static boolean isCurrent(BlockPos pos, String sessionId) {
        if (pos == null || sessionId == null || sessionId.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        ActiveSession active = ACTIVE.get(keyFor(pos, sessionId));
        return active == null || active.sessionId().equals(sessionId);
    }

    private static Object keyFor(BlockPos pos, String sessionId) {
        UUID entityUuid = ClientMinecartAudioAnchors.entityUuid(sessionId);
        return entityUuid != null ? entityUuid : AudioUtils.copyPos(pos);
    }

    private static void cleanup(long now) {
        ACTIVE.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    private record ActiveSession(String sessionId, long expiresAtMillis, long suppressUntilMillis,
            boolean streamStarted) {
    }
}
