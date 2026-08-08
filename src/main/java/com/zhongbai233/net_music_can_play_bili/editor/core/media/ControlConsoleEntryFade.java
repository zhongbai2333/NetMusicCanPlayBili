package com.zhongbai233.net_music_can_play_bili.editor.core.media;

/** 消费者重新建立后的 250ms 单调 smoothstep 淡入。 */
public final class ControlConsoleEntryFade {
    public static final long DURATION_NANOS = 250_000_000L;

    private ControlConsoleEntryFade() {
    }

    public static float gain(long startedNanos, long nowNanos) {
        if (startedNanos <= 0L) {
            return 1.0F;
        }
        long elapsed = nowNanos - startedNanos;
        if (elapsed <= 0L) {
            return 0.0F;
        }
        if (elapsed >= DURATION_NANOS) {
            return 1.0F;
        }
        float t = elapsed / (float) DURATION_NANOS;
        return t * t * (3.0F - 2.0F * t);
    }
}