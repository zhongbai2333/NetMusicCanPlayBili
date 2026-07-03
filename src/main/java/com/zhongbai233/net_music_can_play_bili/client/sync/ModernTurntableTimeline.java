package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.bili.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 现代唱片机客户端稳定媒体时间线。
 *
 * <p>
 * 服务端 {@link ModernTurntableBlockEntity} 的 tick 时钟仍然是权威全局时间线；
 * 但渲染、音频和字幕代码应该读取 {@link #mediaMillis(BlockPos)} 提供的平滑本地时间线。
 * 这样所有本地媒体输出都会对齐到同一个单调时钟，同时仍允许服务端周期性校正，
 * 而不会把每个同步包都变成硬重启或重新 seek。
 * </p>
 */
public final class ModernTurntableTimeline {
    private static final boolean AUDIO_ANCHORED_LOCAL_TIMELINE = Boolean.parseBoolean(
            System.getProperty("ncpb.turntable.timeline.audio_anchor", "true"));
    private static final long AUDIO_ANCHOR_MAX_LAG_MILLIS = Long.getLong(
            "ncpb.turntable.timeline.audio_anchor_max_lag_ms", 2_000L);
    private static final long AUDIO_ANCHOR_MAX_LEAD_MILLIS = Long.getLong(
            "ncpb.turntable.timeline.audio_anchor_max_lead_ms", 500L);
    private static final long CLOCK_PRUNE_INTERVAL_NANOS = Math.max(1_000L,
            Long.getLong("ncpb.turntable.timeline.clock_prune_interval_ms", 30_000L)) * 1_000_000L;
    private static final ConcurrentHashMap<BlockPos, MediaTimelineClock> CLOCKS = new ConcurrentHashMap<>();
    private static volatile long lastClockPruneNanos;

    private ModernTurntableTimeline() {
    }

    public static long mediaMillis(BlockPos turntablePos) {
        TimelineSnapshot snapshot = snapshot(turntablePos);
        return snapshot.localMillis();
    }

    /**
     * 用于字幕/投影等视觉效果的连续本地媒体时钟。
     *
     * <p>
     * {@link #mediaMillis(BlockPos)} 默认会锚定到 OpenAL 的可听位置；当前音频位置以
     * 20Hz tick 公开，适合同步歌词行，但直接驱动滚动动画会产生 50ms 台阶感。
     * 视觉渲染使用本地平滑时钟，保留服务端平滑校正，但不套用音频输出 tick 锚定。
     * </p>
     */
    public static long visualMillis(BlockPos turntablePos) {
        TimelineSnapshot snapshot = snapshot(turntablePos);
        return snapshot.pacingMillis();
    }

    /**
     * 用于音频喂入/泵送的服务端平滑时钟。
     *
     * <p>
     * 不要把这个时钟锚定到音频输出，否则音频 pacing 会追逐自己的已消费位置，
     * 最终可能把 OpenAL 缓冲区喂空。
     * </p>
     */
    public static long pacingMillis(BlockPos turntablePos) {
        TimelineSnapshot snapshot = snapshot(turntablePos);
        return snapshot.pacingMillis();
    }

    public static long serverMillis(BlockPos turntablePos) {
        TimelineSnapshot snapshot = snapshot(turntablePos);
        return snapshot.serverMillis();
    }

    public static TimelineSnapshot snapshot(BlockPos turntablePos) {
        pruneStaleClocksIfNeeded();
        ModernTurntableBlockEntity turntable = turntable(turntablePos);
        if (turntable == null || !turntable.isPlaying()) {
            forget(turntablePos);
            return TimelineSnapshot.EMPTY;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return TimelineSnapshot.EMPTY;
        }
        PlaybackSync.Metadata sync = turntable.getPlaybackSyncMetadata(minecraft.level.getGameTime());
        long rawServerMillis = sync.hasSession() ? sync.elapsedMillis()
                : turntable.getPlaybackElapsedMillis(minecraft.level.getGameTime());
        long totalMillis = sync.hasSession() ? sync.totalMillis()
                : Math.max(0L, turntable.getDurationSeconds()) * 1000L;
        final long serverMillis = clamp(rawServerMillis, totalMillis);
        final long timelineTotalMillis = totalMillis;
        String sessionId = sync.hasSession() ? sync.sessionId() : "";
        BlockPos key = turntablePos.immutable();
        MediaTimelineClock clock = CLOCKS.compute(key, (ignored, existing) -> {
            if (existing == null || !existing.isForSession(sessionId)) {
                return MediaTimelineClock.start(sessionId, serverMillis, timelineTotalMillis);
            }
            existing.observeServer(serverMillis, timelineTotalMillis);
            return existing;
        });
        long pacingMillis = clock != null ? clock.pacingMillis() : serverMillis;
        long localMillis = audioAnchoredMillis(key, pacingMillis, timelineTotalMillis);
        localMillis = clamp(localMillis, timelineTotalMillis);
        pacingMillis = clamp(pacingMillis, timelineTotalMillis);
        return new TimelineSnapshot(sessionId, localMillis, serverMillis, pacingMillis, timelineTotalMillis,
                localMillis - serverMillis);
    }

    private static long audioAnchoredMillis(BlockPos turntablePos, long fallbackMillis, long totalMillis) {
        long fallback = clamp(fallbackMillis, totalMillis);
        if (!AUDIO_ANCHORED_LOCAL_TIMELINE || turntablePos == null) {
            return fallback;
        }
        long audibleMillis = DolbyAudioRegistry.getAudioTimeline(turntablePos).audibleMillis();
        if (audibleMillis < 0L) {
            return fallback;
        }
        long clampedAudio = clamp(audibleMillis, totalMillis);
        long lag = fallback - clampedAudio;
        if (lag > Math.max(0L, AUDIO_ANCHOR_MAX_LAG_MILLIS)
                || -lag > Math.max(0L, AUDIO_ANCHOR_MAX_LEAD_MILLIS)) {
            return fallback;
        }
        return clampedAudio;
    }

    public static void forget(BlockPos turntablePos) {
        if (turntablePos != null) {
            CLOCKS.remove(turntablePos);
        }
    }

    public static void forgetSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        CLOCKS.entrySet().removeIf(entry -> entry.getValue().isForSession(sessionId));
    }

    /** 客户端断连/切世界时主动清空本地媒体时钟。 */
    public static void clear() {
        CLOCKS.clear();
        lastClockPruneNanos = 0L;
    }

    public static int mediaTick(BlockPos turntablePos) {
        long millis = mediaMillis(turntablePos);
        if (millis < 0L) {
            return -1;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, Math.round(millis / 50.0D)));
    }

    public static long relativeNanos(BlockPos turntablePos, long absoluteStartMillis) {
        long millis = mediaMillis(turntablePos);
        if (millis < 0L) {
            return -1L;
        }
        return Math.max(0L, millis - Math.max(0L, absoluteStartMillis)) * 1_000_000L;
    }

    public static ModernTurntableBlockEntity turntable(BlockPos turntablePos) {
        if (turntablePos == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return null;
        }
        if (minecraft.level.getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity turntable) {
            return turntable;
        }
        return null;
    }

    private static long clamp(long millis, long totalMillis) {
        return MediaTimelineClock.clamp(millis, totalMillis);
    }

    private static void pruneStaleClocksIfNeeded() {
        long now = System.nanoTime();
        if (now - lastClockPruneNanos < CLOCK_PRUNE_INTERVAL_NANOS) {
            return;
        }
        lastClockPruneNanos = now;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || CLOCKS.isEmpty()) {
            CLOCKS.clear();
            return;
        }
        CLOCKS.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            return !(minecraft.level.getBlockEntity(pos) instanceof ModernTurntableBlockEntity turntable)
                    || !turntable.isPlaying()
                    || !entry.getValue().isForSession(turntable.getPlaybackSyncMetadata(
                            minecraft.level.getGameTime()).sessionId());
        });
    }

    public record TimelineSnapshot(String sessionId, long localMillis, long serverMillis, long pacingMillis,
            long totalMillis,
            long localDriftMillis) {
        public static final TimelineSnapshot EMPTY = new TimelineSnapshot("", -1L, -1L, -1L, 0L, 0L);
    }
}
