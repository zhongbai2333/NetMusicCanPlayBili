package com.zhongbai233.net_music_can_play_bili.client.sync;

import net.minecraft.core.BlockPos;

/**
 * 现代唱片机所有媒体消费者的统一时钟入口。
 *
 * <p>
 * 内部仍由 {@link ModernTurntableTimeline} 聚合服务端、OpenAL 可听位置和视觉平滑状态，
 * 调用方不再自行选择或拼接 fallback 时钟。
 * </p>
 */
public final class PlaybackClock {
    private PlaybackClock() {
    }

    public static ModernTurntableTimeline.TimelineSnapshot snapshot(BlockPos sourcePos) {
        return ModernTurntableTimeline.snapshot(sourcePos);
    }

    public static long mediaMillis(BlockPos sourcePos) {
        return snapshot(sourcePos).mediaMillis();
    }

    public static long visualMillis(BlockPos sourcePos) {
        return snapshot(sourcePos).visualMillis();
    }

    public static long pacingMillis(BlockPos sourcePos) {
        return snapshot(sourcePos).pacingMillis();
    }

    public static long serverMillis(BlockPos sourcePos) {
        return snapshot(sourcePos).serverMillis();
    }

    public static int mediaTick(BlockPos sourcePos) {
        long millis = mediaMillis(sourcePos);
        return millis < 0L ? -1
                : (int) Math.min(Integer.MAX_VALUE, Math.max(0L, Math.round(millis / 50.0D)));
    }

    public static long relativeNanos(BlockPos sourcePos, long absoluteStartMillis) {
        long millis = mediaMillis(sourcePos);
        return millis < 0L ? -1L : Math.max(0L, millis - Math.max(0L, absoluteStartMillis)) * 1_000_000L;
    }
}