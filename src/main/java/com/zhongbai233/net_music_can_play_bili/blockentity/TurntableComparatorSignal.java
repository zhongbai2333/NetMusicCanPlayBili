package com.zhongbai233.net_music_can_play_bili.blockentity;

/** 现代化唱片机播放进度到比较器信号的纯逻辑换算。 */
public final class TurntableComparatorSignal {
    public static final int MAX_OUTPUT = 15;

    private TurntableComparatorSignal() {
    }

    public static int fromProgress(boolean hasDisc, long elapsedMillis, long durationMillis) {
        if (!hasDisc || durationMillis <= 0L) {
            return 0;
        }
        long clampedElapsed = Math.max(0L, Math.min(durationMillis, elapsedMillis));
        return 1 + (int) Math.min(MAX_OUTPUT - 1L,
                clampedElapsed * (MAX_OUTPUT - 1L) / durationMillis);
    }
}