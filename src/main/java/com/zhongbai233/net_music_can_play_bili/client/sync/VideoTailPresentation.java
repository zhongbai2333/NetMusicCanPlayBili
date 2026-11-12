package com.zhongbai233.net_music_can_play_bili.client.sync;

/** Client publication time; a late tail frame still gets one active display interval. */
public final class VideoTailPresentation {
    private long sequence = -1L, lastNanos, activeNanos;
    private boolean wasPaused;
    public long observe(long frameSequence, long nowNanos, boolean paused) {
        if (sequence != frameSequence) { sequence = frameSequence; activeNanos = 0L; }
        else if (!paused && !wasPaused) activeNanos += Math.max(0L, nowNanos - lastNanos);
        lastNanos = nowNanos;
        wasPaused = paused;
        return activeNanos / 1_000_000L;
    }
}
