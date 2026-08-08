package com.zhongbai233.net_music_can_play_bili.editor.core.media;

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

    public static boolean finished(long startedNanos, long nowNanos) {
        return gain(startedNanos, nowNanos) <= 0.0F;
    }
}