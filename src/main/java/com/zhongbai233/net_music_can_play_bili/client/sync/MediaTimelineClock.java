package com.zhongbai233.net_music_can_play_bili.client.sync;

import net.minecraft.client.Minecraft;

/**
 * 客户端媒体时间线公共时钟。
 *
 * <p>
 * 该实现从现代化唱片机已验证稳定的 LocalClock 抽取而来：服务端时间线仍然权威，
 * 客户端维护单调本地时钟，并通过 hard sync + smooth correction 吸收服务端校正。
 * </p>
 *
 * <p>
 * 语义预留给音频、歌词和未来视频播放共用：
 * </p>
 * <ul>
 * <li>{@link #mediaMillis()}：默认媒体时钟，适合歌词、进度条、同步起播。</li>
 * <li>{@link #visualMillis()}：连续视觉时钟，适合视频帧/滚动歌词动画。</li>
 * <li>{@link #pacingMillis()}：喂入/解码 pacing 时钟，避免音频追逐自己的输出位置。</li>
 * </ul>
 */
public final class MediaTimelineClock {
    public static final long DEFAULT_HARD_SYNC_THRESHOLD_MILLIS = Long.getLong(
            "bili.media.timeline.hard_sync_ms",
            Long.getLong("bili.turntable.timeline.hard_sync_ms", 1_500L));
    public static final long DEFAULT_MAX_SMOOTH_CORRECTION_MILLIS = Long.getLong(
            "bili.media.timeline.max_smooth_correction_ms",
            Long.getLong("bili.turntable.timeline.max_smooth_correction_ms", 80L));
    public static final double DEFAULT_SMOOTH_CORRECTION_RATIO = parseSmoothCorrectionRatio();

    private final String sessionId;
    private final long hardSyncThresholdMillis;
    private final long maxSmoothCorrectionMillis;
    private final double smoothCorrectionRatio;
    private long anchorNanos;
    private long anchorMillis;
    private long totalMillis;
    private long lastLocalMillis;
    private long pauseStartedNanos;
    private long lastObservedServerMillis;

    private MediaTimelineClock(String sessionId, long serverMillis, long totalMillis,
            long hardSyncThresholdMillis, long maxSmoothCorrectionMillis, double smoothCorrectionRatio) {
        this.sessionId = sessionId != null ? sessionId : "";
        this.hardSyncThresholdMillis = Math.max(0L, hardSyncThresholdMillis);
        this.maxSmoothCorrectionMillis = Math.max(0L, maxSmoothCorrectionMillis);
        this.smoothCorrectionRatio = Math.max(0.0D, Math.min(1.0D, smoothCorrectionRatio));
        this.anchorNanos = System.nanoTime();
        this.totalMillis = Math.max(0L, totalMillis);
        this.anchorMillis = clamp(serverMillis, this.totalMillis);
        this.lastLocalMillis = this.anchorMillis;
        this.lastObservedServerMillis = this.anchorMillis;
    }

    public static MediaTimelineClock start(String sessionId, long serverMillis, long totalMillis) {
        return new MediaTimelineClock(sessionId, serverMillis, totalMillis,
                DEFAULT_HARD_SYNC_THRESHOLD_MILLIS,
                DEFAULT_MAX_SMOOTH_CORRECTION_MILLIS,
                DEFAULT_SMOOTH_CORRECTION_RATIO);
    }

    public static MediaTimelineClock start(String sessionId, long serverMillis, long totalMillis,
            long hardSyncThresholdMillis, long maxSmoothCorrectionMillis, double smoothCorrectionRatio) {
        return new MediaTimelineClock(sessionId, serverMillis, totalMillis, hardSyncThresholdMillis,
                maxSmoothCorrectionMillis, smoothCorrectionRatio);
    }

    public String sessionId() {
        return sessionId;
    }

    public synchronized boolean isForSession(String candidate) {
        return sessionId.equals(candidate != null ? candidate : "");
    }

    public synchronized void observeServer(long serverMillis, long newTotalMillis) {
        totalMillis = Math.max(0L, newTotalMillis);
        long server = clamp(serverMillis, totalMillis);
        lastObservedServerMillis = server;
        if (updatePausedState()) {
            return;
        }
        long local = localMillisUnlocked();
        long drift = server - local;
        if (Math.abs(drift) >= hardSyncThresholdMillis) {
            anchorNanos = System.nanoTime();
            anchorMillis = server;
            lastLocalMillis = anchorMillis;
            return;
        }
        long correction = Math.round(drift * smoothCorrectionRatio);
        if (maxSmoothCorrectionMillis > 0L) {
            correction = Math.max(-maxSmoothCorrectionMillis, Math.min(maxSmoothCorrectionMillis, correction));
        }
        if (correction != 0L) {
            anchorMillis = clamp(anchorMillis + correction, totalMillis);
        }
    }

    public synchronized void reanchor(long mediaMillis, long newTotalMillis) {
        totalMillis = Math.max(0L, newTotalMillis);
        long media = clamp(mediaMillis, totalMillis);
        anchorNanos = System.nanoTime();
        anchorMillis = media;
        lastLocalMillis = media;
        pauseStartedNanos = 0L;
        lastObservedServerMillis = media;
    }

    public synchronized long mediaMillis() {
        return localMillis();
    }

    public synchronized long visualMillis() {
        return localMillis();
    }

    public synchronized long pacingMillis() {
        return localMillis();
    }

    public synchronized long serverMillis() {
        return lastObservedServerMillis;
    }

    public synchronized long totalMillis() {
        return totalMillis;
    }

    public synchronized TimelineSnapshot snapshot() {
        long local = localMillis();
        return new TimelineSnapshot(sessionId, local, local, local, lastObservedServerMillis, totalMillis,
                local - lastObservedServerMillis);
    }

    public synchronized int mediaTick() {
        long millis = mediaMillis();
        if (millis < 0L) {
            return -1;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, Math.round(millis / 50.0D)));
    }

    public synchronized long relativeNanos(long absoluteStartMillis) {
        long millis = mediaMillis();
        if (millis < 0L) {
            return -1L;
        }
        return Math.max(0L, millis - Math.max(0L, absoluteStartMillis)) * 1_000_000L;
    }

    private long localMillis() {
        if (updatePausedState()) {
            return lastLocalMillis;
        }
        long value = localMillisUnlocked();
        if (value < lastLocalMillis && lastLocalMillis - value < hardSyncThresholdMillis) {
            value = lastLocalMillis;
        }
        lastLocalMillis = value;
        return value;
    }

    private long localMillisUnlocked() {
        long elapsedMillis = Math.max(0L, (System.nanoTime() - anchorNanos) / 1_000_000L);
        return clamp(anchorMillis + elapsedMillis, totalMillis);
    }

    private boolean updatePausedState() {
        Minecraft minecraft = Minecraft.getInstance();
        boolean paused = minecraft != null && minecraft.isPaused();
        long now = System.nanoTime();
        if (paused) {
            if (pauseStartedNanos == 0L) {
                pauseStartedNanos = now;
            }
            return true;
        }
        if (pauseStartedNanos != 0L) {
            anchorNanos += Math.max(0L, now - pauseStartedNanos);
            pauseStartedNanos = 0L;
        }
        return false;
    }

    public static long clamp(long millis, long totalMillis) {
        long value = Math.max(0L, millis);
        long total = Math.max(0L, totalMillis);
        return total > 0L ? Math.min(total, value) : value;
    }

    private static double parseSmoothCorrectionRatio() {
        String raw = System.getProperty("bili.media.timeline.smooth_correction_ratio",
                System.getProperty("bili.turntable.timeline.smooth_correction_ratio", "0.12"));
        try {
            double parsed = Double.parseDouble(raw);
            if (!Double.isFinite(parsed)) {
                return 0.12D;
            }
            return Math.max(0.0D, Math.min(1.0D, parsed));
        } catch (NumberFormatException ignored) {
            return 0.12D;
        }
    }

    public record TimelineSnapshot(String sessionId, long mediaMillis, long visualMillis, long pacingMillis,
            long serverMillis, long totalMillis, long mediaDriftMillis) {
        public static final TimelineSnapshot EMPTY = new TimelineSnapshot("", -1L, -1L, -1L, -1L, 0L, 0L);
    }
}
