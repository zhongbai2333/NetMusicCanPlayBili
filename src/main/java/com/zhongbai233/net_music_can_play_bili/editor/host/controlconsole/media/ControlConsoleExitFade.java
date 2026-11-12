package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

/** 中控台消费者突发退出的单调时间包络。 */
public final class ControlConsoleExitFade {
    public static final long DURATION_NANOS = 250_000_000L;

    private ControlConsoleExitFade() {
    }

    public static float gain(long startedNanos, long nowNanos) {
        if (nowNanos <= startedNanos) {
            return 1.0F;
        }
        long elapsed = nowNanos - startedNanos;
        if (elapsed >= DURATION_NANOS || elapsed < 0L) {
            return 0.0F;
        }
        double t = elapsed / (double) DURATION_NANOS;
        double smooth = t * t * (3.0D - 2.0D * t);
        return (float) (1.0D - smooth);
    }

    /** Retire on the last update that can fit the observed callback cadence in the total budget. */
    public static float gainWithinUpdateBudget(long startedNanos, long nowNanos, long updateIntervalNanos) {
        if (nowNanos < startedNanos) {
            return 1.0F;
        }
        long elapsed = nowNanos - startedNanos;
        long reserve = Math.clamp(updateIntervalNanos, 0L, DURATION_NANOS);
        if (elapsed < 0L || elapsed >= DURATION_NANOS
                || DURATION_NANOS - elapsed <= reserve) {
            return 0.0F;
        }
        return gain(startedNanos, nowNanos);
    }

    public static boolean finished(long startedNanos, long nowNanos) {
        return gain(startedNanos, nowNanos) <= 0.0F;
    }
}