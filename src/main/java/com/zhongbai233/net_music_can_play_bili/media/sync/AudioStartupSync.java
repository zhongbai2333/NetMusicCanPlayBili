package com.zhongbai233.net_music_can_play_bili.media.sync;

/**
 * Pure timing helpers for compensating audio setup latency before playback
 * starts.
 */
public final class AudioStartupSync {
    private AudioStartupSync() {
    }

    public static long elapsedSinceCaptureMillis(long capturedNanos, long nowNanos) {
        if (capturedNanos <= 0L || nowNanos <= capturedNanos) {
            return 0L;
        }
        return (nowNanos - capturedNanos) / 1_000_000L;
    }

    public static long compensatedElapsedMillis(long capturedElapsedMillis, long totalMillis,
            long capturedNanos, long nowNanos) {
        return compensatedOffsetMillis(capturedElapsedMillis, totalMillis,
                elapsedSinceCaptureMillis(capturedNanos, nowNanos));
    }

    public static long compensatedOffsetMillis(long capturedElapsedMillis, long totalMillis,
            long additionalSkippedMillis) {
        long elapsed = saturatedAdd(Math.max(0L, capturedElapsedMillis), Math.max(0L, additionalSkippedMillis));
        return totalMillis > 0L ? Math.min(elapsed, totalMillis) : elapsed;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}