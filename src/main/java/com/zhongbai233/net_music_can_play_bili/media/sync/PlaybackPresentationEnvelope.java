package com.zhongbai233.net_music_can_play_bili.media.sync;

/** Starts a fresh smooth fade whenever decoded media becomes genuinely presentable. */
public final class PlaybackPresentationEnvelope {
    public static final long DURATION_NANOS = 300_000_000L;

    private boolean presenting;
    private long enteredNanos;

    public synchronized float gain(boolean presentable, long nowNanos) {
        if (!presentable) {
            presenting = false;
            enteredNanos = 0L;
            return 0.0F;
        }
        if (!presenting) {
            presenting = true;
            enteredNanos = nowNanos;
            return 0.0F;
        }
        long elapsed = nowNanos - enteredNanos;
        if (elapsed <= 0L) {
            return 0.0F;
        }
        if (elapsed >= DURATION_NANOS) {
            return 1.0F;
        }
        float t = elapsed / (float) DURATION_NANOS;
        return t * t * (3.0F - 2.0F * t);
    }

    public synchronized void reset() {
        presenting = false;
        enteredNanos = 0L;
    }
}
