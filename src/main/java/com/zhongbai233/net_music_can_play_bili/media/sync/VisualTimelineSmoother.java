package com.zhongbai233.net_music_can_play_bili.media.sync;

/**
 * 将可听媒体位置转换为连续、单调的视觉时钟；不参与音频 producer pacing。
 */
public final class VisualTimelineSmoother {
    private final long hardSyncMillis;
    private final long maxCorrectionMillis;
    private final double correctionRatio;
    private long anchorNanos;
    private long anchorMillis;
    private long lastMillis = -1L;

    public VisualTimelineSmoother(long hardSyncMillis, long maxCorrectionMillis, double correctionRatio) {
        this.hardSyncMillis = Math.max(0L, hardSyncMillis);
        this.maxCorrectionMillis = Math.max(0L, maxCorrectionMillis);
        this.correctionRatio = Math.max(0.0D, Math.min(1.0D, correctionRatio));
    }

    public synchronized long sample(long mediaMillis, long totalMillis, long nowNanos) {
        long media = clamp(mediaMillis, totalMillis);
        if (lastMillis < 0L || nowNanos <= 0L) {
            return reset(media, totalMillis, nowNanos);
        }
        long current = extrapolated(nowNanos, totalMillis);
        long drift = media - current;
        if (drift >= hardSyncMillis) {
            return reset(media, totalMillis, nowNanos);
        }
        if (drift <= -hardSyncMillis) {
            // 同一 session 内不向后跳；冻结视觉位置，等待媒体时钟追上。
            anchorNanos = nowNanos;
            anchorMillis = lastMillis;
            return lastMillis;
        }
        long correction = Math.round(drift * correctionRatio);
        correction = Math.max(-maxCorrectionMillis, Math.min(maxCorrectionMillis, correction));
        long candidate = clamp(current + correction, totalMillis);
        if (candidate < lastMillis) {
            candidate = lastMillis;
        }
        anchorNanos = nowNanos;
        anchorMillis = candidate;
        lastMillis = candidate;
        return candidate;
    }

    public synchronized long reset(long mediaMillis, long totalMillis, long nowNanos) {
        anchorNanos = Math.max(0L, nowNanos);
        anchorMillis = clamp(mediaMillis, totalMillis);
        lastMillis = anchorMillis;
        return anchorMillis;
    }

    private long extrapolated(long nowNanos, long totalMillis) {
        long elapsedMillis = nowNanos > anchorNanos ? (nowNanos - anchorNanos) / 1_000_000L : 0L;
        return clamp(saturatedAdd(anchorMillis, elapsedMillis), totalMillis);
    }

    private static long clamp(long millis, long totalMillis) {
        long value = Math.max(0L, millis);
        return totalMillis > 0L ? Math.min(value, totalMillis) : value;
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }
}