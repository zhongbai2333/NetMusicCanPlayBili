package com.zhongbai233.net_music_can_play_bili.media.sync;

/**
 * Reset-proof runtime clock for media playback. Persist elapsed milliseconds,
 * never the process-local nanosecond anchor.
 */
public final class MonotonicMediaClock {
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private MonotonicMediaClock() {
    }

    public static long nowNanos() {
        return System.nanoTime();
    }

    /** Reset-proof 20 Hz runtime tick for legacy media-session calculations. */
    public static long nowTick() {
        return Math.floorDiv(nowNanos(), 50_000_000L);
    }

    public static Anchor paused(long elapsedMillis) {
        return new Anchor(Math.max(0L, elapsedMillis), 0L, false);
    }

    public static Anchor running(long elapsedMillis, long nowNanos) {
        return new Anchor(Math.max(0L, elapsedMillis), nowNanos, true);
    }

    public static long remainingSeconds(Anchor anchor, long durationMillis, long nowNanos) {
        if (durationMillis <= 0L) {
            return 0L;
        }
        long remainingMillis = Math.max(0L, durationMillis - anchor.elapsedMillis(nowNanos, durationMillis));
        return (remainingMillis + 999L) / 1_000L;
    }

    public record Anchor(long baseElapsedMillis, long anchorNanos, boolean running) {
        public Anchor {
            baseElapsedMillis = Math.max(0L, baseElapsedMillis);
        }

        public long elapsedMillis(long nowNanos, long durationMillis) {
            long elapsed = baseElapsedMillis;
            if (running) {
                long deltaNanos = Math.max(0L, nowNanos - anchorNanos);
                elapsed = saturatedAdd(elapsed, deltaNanos / NANOS_PER_MILLI);
            }
            return clamp(elapsed, durationMillis);
        }

        public Anchor pause(long nowNanos, long durationMillis) {
            return paused(elapsedMillis(nowNanos, durationMillis));
        }

        public Anchor resume(long nowNanos, long durationMillis) {
            return MonotonicMediaClock.running(elapsedMillis(nowNanos, durationMillis), nowNanos);
        }

        public Anchor seek(long elapsedMillis, long nowNanos, boolean shouldRun) {
            return shouldRun
                    ? MonotonicMediaClock.running(elapsedMillis, nowNanos)
                    : MonotonicMediaClock.paused(elapsedMillis);
        }

        public Anchor reanchor(long nowNanos, long durationMillis) {
            long elapsed = elapsedMillis(nowNanos, durationMillis);
            return running ? MonotonicMediaClock.running(elapsed, nowNanos) : MonotonicMediaClock.paused(elapsed);
        }
    }

    private static long clamp(long elapsedMillis, long durationMillis) {
        long nonNegative = Math.max(0L, elapsedMillis);
        return durationMillis > 0L ? Math.min(nonNegative, durationMillis) : nonNegative;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
