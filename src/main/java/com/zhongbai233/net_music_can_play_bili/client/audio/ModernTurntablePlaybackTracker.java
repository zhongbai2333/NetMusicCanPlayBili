package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

public final class ModernTurntablePlaybackTracker {
    private static final long STOP_GRACE_MILLIS = 5_000L;
    private static final long DUPLICATE_SUPPRESS_MILLIS = 1_500L;
    private static final ConcurrentHashMap<Object, ClientPlaybackSession> ACTIVE = new ConcurrentHashMap<>();
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
        ClientPlaybackSession previous = ACTIVE.get(key);
        if (previous != null && previous.sessionId().equals(sessionId)) {
            if (previous.expiresAtMillis() > now) {
                return false;
            }
            if (previous.suppressUntilMillis() > now) {
                return false;
            }
        }
        ClientPlaybackSession next = new ClientPlaybackSession(sessionId, expiresAt,
                now + DUPLICATE_SUPPRESS_MILLIS);
        AtomicReference<ClientPlaybackSession> replaced = new AtomicReference<>();
        ACTIVE.compute(key, (ignored, current) -> {
            if (current != null && current.sessionId().equals(sessionId)
                    && (current.expiresAtMillis() > now || current.suppressUntilMillis() > now)) {
                replaced.set(next);
                return current;
            }
            replaced.set(current);
            return next;
        });
        ClientPlaybackSession old = replaced.get();
        if (old == next) {
            return false;
        }
        if (old != null) {
            old.cancel();
        }
        return true;
    }

    public static void markStreamStarted(BlockPos pos, String sessionId) {
        if (pos == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        ClientPlaybackSession active = ACTIVE.get(keyFor(pos, sessionId));
        if (active != null && active.sessionId().equals(sessionId)) {
            active.transitionTo(ClientPlaybackSession.State.PLAYING);
        }
    }

    public static void registerSound(ModernTurntableSound sound, BlockPos pos, String sessionId) {
        if (sound != null) {
            ACTIVE_SOUNDS.put(sound, Boolean.TRUE);
            ClientPlaybackSession active = ACTIVE.get(keyFor(pos, sessionId));
            if (active == null || !active.sessionId().equals(sessionId)) {
                stopSound(sound);
                return;
            }
            active.transitionTo(ClientPlaybackSession.State.BUFFERING);
            active.onCancel(() -> stopSound(sound));
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
        Object key = keyFor(pos, sessionId);
        ClientPlaybackSession active = ACTIVE.get(key);
        if (active != null && active.sessionId().equals(sessionId) && ACTIVE.remove(key, active)) {
            active.cancel();
        }
    }

    /** 客户端切世界/断连时清空全部跟踪记录，避免旧 session 的 streamStarted 标记阻断重连后的同步 */
    public static void clear() {
        for (ClientPlaybackSession session : ACTIVE.values()) {
            session.cancel();
        }
        ACTIVE.clear();
        ClientMinecartAudioAnchors.clear();
    }

    public static boolean isCurrent(BlockPos pos, String sessionId) {
        if (pos == null || sessionId == null || sessionId.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        ClientPlaybackSession active = ACTIVE.get(keyFor(pos, sessionId));
        return active == null || active.sessionId().equals(sessionId);
    }

    /** 指定 session 必须仍被登记且未取消；用于异步任务提交结果前的严格校验。 */
    public static boolean isActiveSession(BlockPos pos, String sessionId) {
        if (pos == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        ClientPlaybackSession active = ACTIVE.get(keyFor(pos, sessionId));
        return active != null && active.sessionId().equals(sessionId) && !active.isCancelled()
                && !active.isTerminal();
    }

    /**
     * 返回网络播放入口为该唱片机登记的权威客户端 session。
     * 方块实体负责提供服务端播放时间观测，不再由音频输出层根据方块 NBT 重建 session。
     */
    public static String currentSessionId(BlockPos pos) {
        return currentSessionId(pos, "");
    }

    /** sessionHint 用于解析移动唱片机的实体 UUID key。 */
    public static String currentSessionId(BlockPos pos, String sessionHint) {
        if (pos == null) {
            return "";
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        Object key = sessionHint != null && !sessionHint.isBlank()
                ? keyFor(pos, sessionHint)
                : AudioUtils.copyPos(pos);
        ClientPlaybackSession active = ACTIVE.get(key);
        return active != null ? active.sessionId() : "";
    }

    public static void markRecovering(BlockPos pos, String sessionId) {
        ClientPlaybackSession active = ACTIVE.get(keyFor(pos, sessionId));
        if (active != null && active.sessionId().equals(sessionId)) {
            active.transitionTo(ClientPlaybackSession.State.RECOVERING);
        }
    }

    public static boolean onCancel(BlockPos pos, String sessionId, Runnable action) {
        ClientPlaybackSession active = session(pos, sessionId);
        if (active == null || active.isCancelled() || active.isTerminal()) {
            return false;
        }
        active.onCancel(action);
        return true;
    }

    static ClientPlaybackSession session(BlockPos pos, String sessionId) {
        ClientPlaybackSession active = ACTIVE.get(keyFor(pos, sessionId));
        return active != null && active.sessionId().equals(sessionId) ? active : null;
    }

    public static void fail(BlockPos pos, String sessionId) {
        ClientPlaybackSession active = session(pos, sessionId);
        if (active != null) {
            active.fail();
            if (ACTIVE.remove(keyFor(pos, sessionId), active)) {
                active.cancel();
            }
        }
    }

    private static Object keyFor(BlockPos pos, String sessionId) {
        UUID entityUuid = ClientMinecartAudioAnchors.entityUuid(sessionId);
        return entityUuid != null ? entityUuid : AudioUtils.copyPos(pos);
    }

    private static void cleanup(long now) {
        ACTIVE.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAtMillis() > now) {
                return false;
            }
            entry.getValue().cancel();
            return true;
        });
    }

    private static void stopSound(ModernTurntableSound sound) {
        ACTIVE_SOUNDS.remove(sound);
        Minecraft minecraft = Minecraft.getInstance();
        Runnable stop = () -> {
            sound.stopFromTracker();
            minecraft.getSoundManager().stop(sound);
        };
        if (minecraft.isSameThread()) {
            stop.run();
        } else {
            minecraft.execute(stop);
        }
    }
}
