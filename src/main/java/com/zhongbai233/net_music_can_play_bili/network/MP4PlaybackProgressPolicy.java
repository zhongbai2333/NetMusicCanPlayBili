package com.zhongbai233.net_music_can_play_bili.network;

/** Pure selection and clamping rules for MP4 playback progress. */
final class MP4PlaybackProgressPolicy {
    private MP4PlaybackProgressPolicy() {
    }

    static long currentElapsed(RuntimeProgress runtime, Long persistedElapsed, long fallback) {
        if (runtime != null) {
            return Math.max(0L, runtime.elapsedMillis());
        }
        if (persistedElapsed != null) {
            return Math.max(0L, persistedElapsed);
        }
        return Math.max(0L, fallback);
    }

    static long queueElapsed(int queueIndex, RuntimeProgress runtime, long persistedOrFallback) {
        if (runtime != null && runtime.queueIndex() == queueIndex) {
            return Math.max(0L, runtime.elapsedMillis());
        }
        return Math.max(0L, persistedOrFallback);
    }

    static long elapsedFromProgress(int durationSeconds, int progressPerMille) {
        if (durationSeconds <= 0) {
            return 0L;
        }
        long durationMillis = durationSeconds * 1000L;
        return Math.round(progressPerMille / 1000.0D * durationMillis);
    }

    static long clampTarget(int durationSeconds, long elapsedMillis) {
        if (durationSeconds <= 0) {
            return 0L;
        }
        long max = Math.max(0L, durationSeconds * 1000L - 50L);
        return Math.max(0L, Math.min(max, elapsedMillis));
    }

    static int progressPerMille(long elapsedMillis, int durationSeconds) {
        long durationMillis = Math.max(1L, (long) Math.max(1, durationSeconds) * 1000L);
        long elapsed = Math.max(0L, Math.min(durationMillis, elapsedMillis));
        return Math.max(0, Math.min(1000, (int) Math.round(elapsed * 1000.0D / durationMillis)));
    }

    record RuntimeProgress(int queueIndex, long elapsedMillis) {
    }
}
