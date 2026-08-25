package com.zhongbai233.net_music_can_play_bili.media.audio;

/** Bidirectional, continuously retargetable smoothstep fade for acoustic boundaries. */
public final class AreaAudioBoundaryEnvelope {
    public static final long DEFAULT_FADE_IN_NANOS = 650_000_000L;
    public static final long DEFAULT_FADE_OUT_NANOS = 800_000_000L;

    private final long fadeInNanos;
    private final long fadeOutNanos;
    private boolean initialized;
    private boolean targetAllowed;
    private float startGain;
    private float targetGain;
    private long transitionStartedNanos;

    public AreaAudioBoundaryEnvelope() {
        this(DEFAULT_FADE_IN_NANOS, DEFAULT_FADE_OUT_NANOS);
    }

    public AreaAudioBoundaryEnvelope(long fadeInNanos, long fadeOutNanos) {
        this.fadeInNanos = Math.max(1L, fadeInNanos);
        this.fadeOutNanos = Math.max(1L, fadeOutNanos);
    }

    public synchronized float gain(boolean allowed, long nowNanos) {
        if (!initialized) {
            initialized = true;
            targetAllowed = allowed;
            startGain = targetGain = allowed ? 1.0F : 0.0F;
            transitionStartedNanos = nowNanos;
            return targetGain;
        }
        float current = interpolated(nowNanos);
        if (allowed != targetAllowed) {
            targetAllowed = allowed;
            startGain = current;
            targetGain = allowed ? 1.0F : 0.0F;
            transitionStartedNanos = nowNanos;
            return current;
        }
        return current;
    }

    private float interpolated(long nowNanos) {
        if (startGain == targetGain) {
            return targetGain;
        }
        long duration = targetGain > startGain ? fadeInNanos : fadeOutNanos;
        float progress = Math.clamp((float) Math.max(0L, nowNanos - transitionStartedNanos) / duration,
                0.0F, 1.0F);
        float smooth = progress * progress * (3.0F - 2.0F * progress);
        return startGain + (targetGain - startGain) * smooth;
    }
}
