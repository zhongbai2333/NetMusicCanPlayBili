package com.zhongbai233.net_music_can_play_bili.media.sync;

/**
 * Identifies one in-process asynchronous media resolve generation.
 *
 * <p>The initial value is reserved for "no request yet". Advancing from
 * {@link Long#MAX_VALUE} therefore wraps to {@code 1} instead of overflowing
 * into the negative range or reusing the initial sentinel.</p>
 */
public record ResolveGeneration(long value) {
    public ResolveGeneration {
        if (value < 0L) {
            throw new IllegalArgumentException("resolve generation must not be negative: " + value);
        }
    }

    public static ResolveGeneration initial() {
        return new ResolveGeneration(0L);
    }

    public static ResolveGeneration of(long value) {
        return new ResolveGeneration(value);
    }

    public ResolveGeneration next() {
        return new ResolveGeneration(value == Long.MAX_VALUE ? 1L : value + 1L);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
