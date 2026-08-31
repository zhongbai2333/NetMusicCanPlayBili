package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

public final class ModernTurntablePlaybackTracker {
    private static final long STOP_GRACE_MILLIS = 5_000L;
    private static final long DUPLICATE_SUPPRESS_MILLIS = 1_500L;
    private static final long STOP_TOMBSTONE_MILLIS = 30_000L;
    private static final ConcurrentHashMap<Object, ClientPlaybackSession> ACTIVE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SyncedMediaSound, Boolean> ACTIVE_SOUNDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SyncedMediaSound, BlockPos> ACTIVE_SOUND_POSITIONS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<StoppedKey, Long> EXPLICITLY_STOPPED = new ConcurrentHashMap<>();


    private ModernTurntablePlaybackTracker() {
    }

    public static boolean tryStart(BlockPos pos, String sessionId, int remainingSeconds) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (pos == null || parsedSessionId == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        if (isExplicitlyStopped(pos, parsedSessionId, now)) {
            return false;
        }
        long expiresAt = now + Math.max(1, remainingSeconds) * 1000L + STOP_GRACE_MILLIS;
        Object key = keyFor(pos, parsedSessionId);
        ClientPlaybackSession previous = ACTIVE.get(key);
        if (previous != null && previous.playbackSessionId().equals(parsedSessionId)) {
            if (previous.expiresAtMillis() > now) {
                return false;
            }
            if (previous.suppressUntilMillis() > now) {
                return false;
            }
        }
        ClientPlaybackSession next = new ClientPlaybackSession(parsedSessionId, expiresAt,
                now + DUPLICATE_SUPPRESS_MILLIS);
        AtomicReference<ClientPlaybackSession> replaced = new AtomicReference<>();
        ACTIVE.compute(key, (ignored, current) -> {
            if (current != null && current.playbackSessionId().equals(parsedSessionId)
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
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (pos == null || parsedSessionId == null) {
            return;
        }
        ClientPlaybackSession active = ACTIVE.get(keyFor(pos, parsedSessionId));
        if (active != null && active.playbackSessionId().equals(parsedSessionId)) {
            active.transitionTo(ClientPlaybackSession.State.PLAYING);
        }
    }

    public static void registerSound(SyncedMediaSound sound, BlockPos pos, String sessionId) {
        if (sound != null) {
            ACTIVE_SOUNDS.put(sound, Boolean.TRUE);
            if (pos != null) {
                ACTIVE_SOUND_POSITIONS.put(sound, AudioUtils.copyPos(pos));
            }
            PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
            ClientPlaybackSession active = pos != null && parsedSessionId != null
                    ? ACTIVE.get(keyFor(pos, parsedSessionId))
                    : null;
            if (active == null || !active.playbackSessionId().equals(parsedSessionId)) {
                stopSound(sound);
                return;
            }
            active.transitionTo(ClientPlaybackSession.State.BUFFERING);
            active.onCancel(() -> stopSound(sound));
        }
    }

    public static void unregisterSound(SyncedMediaSound sound) {
        if (sound != null) {
            ACTIVE_SOUNDS.remove(sound);
            ACTIVE_SOUND_POSITIONS.remove(sound);
        }
    }

    public static void stopAllSounds() {
        Minecraft minecraft = Minecraft.getInstance();
        for (SyncedMediaSound sound : ACTIVE_SOUNDS.keySet()) {
            sound.stopFromTracker();
            if (minecraft != null) {
                minecraft.getSoundManager().stop(sound);
            }
        }
        ACTIVE_SOUNDS.clear();
        ACTIVE_SOUND_POSITIONS.clear();
        clear();
    }

    public static void retireForDemandIdle(BlockPos pos, String sessionId) {
        if (pos == null || PlaybackSessionId.parse(sessionId).isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        for (SyncedMediaSound sound : ACTIVE_SOUNDS.keySet()) {
            if (!sessionId.equals(sound.sessionId()) || !pos.equals(ACTIVE_SOUND_POSITIONS.get(sound))) {
                continue;
            }
            ACTIVE_SOUNDS.remove(sound);
            ACTIVE_SOUND_POSITIONS.remove(sound);
            sound.stopForDemandIdle();
            if (minecraft != null) {
                minecraft.getSoundManager().stop(sound);
            }
        }
    }

    /** Records a server-authoritative stop before cancelling the matching physical/logical session. */
    public static void explicitStop(BlockPos pos, String sessionId) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (pos == null || parsedSessionId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        EXPLICITLY_STOPPED.put(stoppedKey(pos, parsedSessionId), now + STOP_TOMBSTONE_MILLIS);
        finish(pos, parsedSessionId.value());
    }

    public static void finish(BlockPos pos, String sessionId) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (pos == null || parsedSessionId == null) {
            return;
        }
        Object key = keyFor(pos, parsedSessionId);
        ClientPlaybackSession active = ACTIVE.get(key);
        if (active != null
                && ModernTurntableStopPolicy.decide(parsedSessionId.value(), active.sessionId())
                        == ModernTurntableStopPolicy.Decision.STOP_EXACT
                && ACTIVE.remove(key, active)) {
            active.cancel();
        }
    }

    /** 客户端切世界/断连时清空全部跟踪记录，避免旧 session 的 streamStarted 标记阻断重连后的同步 */
    public static void clear() {
        for (ClientPlaybackSession session : ACTIVE.values()) {
            session.cancel();
        }
        ACTIVE.clear();
        EXPLICITLY_STOPPED.clear();
        ClientMinecartAudioAnchors.clear();
    }

    public static boolean isCurrent(BlockPos pos, String sessionId) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (pos == null || parsedSessionId == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        if (isExplicitlyStopped(pos, parsedSessionId, now)) {
            return false;
        }
        ClientPlaybackSession active = ACTIVE.get(keyFor(pos, parsedSessionId));
        return active == null || active.playbackSessionId().equals(parsedSessionId);
    }

    /** Explicit server stops dominate delayed playback packets and scheduled recovery for the same session. */
    public static boolean wasExplicitlyStopped(BlockPos pos, String sessionId) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (pos == null || parsedSessionId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        return isExplicitlyStopped(pos, parsedSessionId, now);
    }

    /** 指定 session 必须仍被登记且未取消；用于异步任务提交结果前的严格校验。 */
    public static boolean isActiveSession(BlockPos pos, String sessionId) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (pos == null || parsedSessionId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        ClientPlaybackSession active = ACTIVE.get(keyFor(pos, parsedSessionId));
        return active != null && active.playbackSessionId().equals(parsedSessionId) && !active.isCancelled()
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
        PlaybackSessionId parsedHint = PlaybackSessionId.parse(sessionHint).orElse(null);
        Object key = parsedHint != null
                ? keyFor(pos, parsedHint)
                : AudioUtils.copyPos(pos);
        ClientPlaybackSession active = ACTIVE.get(key);
        return active != null ? active.sessionId() : "";
    }

    public static void markRecovering(BlockPos pos, String sessionId) {
        ClientPlaybackSession active = session(pos, sessionId);
        if (active != null) {
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

    /** 将同一职责的异步资源原子换代；会话已失效时由调用方保留兼容清理策略。 */
    public static boolean replaceResource(BlockPos pos, String sessionId, String slot, Runnable cancellationAction) {
        ClientPlaybackSession active = session(pos, sessionId);
        return active != null && active.replaceResource(slot, cancellationAction);
    }

    static ClientPlaybackSession session(BlockPos pos, String sessionId) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (pos == null || parsedSessionId == null) {
            return null;
        }
        ClientPlaybackSession active = ACTIVE.get(keyFor(pos, parsedSessionId));
        return active != null && active.playbackSessionId().equals(parsedSessionId) ? active : null;
    }

    public static void fail(BlockPos pos, String sessionId) {
        ClientPlaybackSession active = session(pos, sessionId);
        if (active != null) {
            active.fail();
            if (ACTIVE.remove(keyFor(pos, active.playbackSessionId()), active)) {
                active.cancel();
            }
        }
    }

    private static Object keyFor(BlockPos pos, PlaybackSessionId sessionId) {
        UUID entityUuid = ClientMinecartAudioAnchors.entityUuid(sessionId.value());
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
        EXPLICITLY_STOPPED.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private static boolean isExplicitlyStopped(BlockPos pos, PlaybackSessionId sessionId, long now) {
        Long expiresAt = EXPLICITLY_STOPPED.get(stoppedKey(pos, sessionId));
        return expiresAt != null && expiresAt > now;
    }

    private static StoppedKey stoppedKey(BlockPos pos, PlaybackSessionId sessionId) {
        return new StoppedKey(AudioUtils.copyPos(pos), sessionId);
    }

    private record StoppedKey(BlockPos pos, PlaybackSessionId sessionId) {
    }

    private static void stopSound(SyncedMediaSound sound) {
        ACTIVE_SOUNDS.remove(sound);
        ACTIVE_SOUND_POSITIONS.remove(sound);
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
