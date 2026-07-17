package com.zhongbai233.net_music_can_play_bili.media.sync;

/** Stereo/Dolby 共用的纯逻辑音频同步决策。所有位置均使用 50ms tick。 */
public record AudioSyncPolicy(long catchUpStartTicks, long catchUpFullTicks, long outputLagFlushTicks,
        long fedNearTargetTicks, long flushAheadTicks) {
    public static AudioSyncPolicy fromSystemProperties() {
        return new AudioSyncPolicy(
                Long.getLong("bili.audio.sync.catch_up_start_ticks", 8L),
                Long.getLong("bili.audio.sync.catch_up_full_ticks", 28L),
                Long.getLong("bili.audio.timeline.flush_output_lag_ticks", 40L),
                Long.getLong("bili.audio.timeline.output_lag_fed_near_target_ticks", 8L),
                Long.getLong("ncpb.bili.audio.timeline.flush_ahead_ticks", 12L));
    }

    public AudioSyncPolicy {
        catchUpStartTicks = Math.max(0L, catchUpStartTicks);
        catchUpFullTicks = Math.max(catchUpStartTicks + 1L, catchUpFullTicks);
        outputLagFlushTicks = Math.max(0L, outputLagFlushTicks);
        fedNearTargetTicks = Math.max(0L, fedNearTargetTicks);
        flushAheadTicks = Math.max(0L, flushAheadTicks);
    }

    public boolean isAhead(long fedTicks, long audibleTicks, long targetTicks) {
        return isFiniteTarget(targetTicks)
                && (fedTicks >= 0L && fedTicks > targetTicks
                        || audibleTicks >= 0L && audibleTicks > targetTicks);
    }

    public boolean shouldFlushAhead(long fedTicks, long audibleTicks, long targetTicks) {
        return isFiniteTarget(targetTicks)
                && (fedTicks >= 0L && fedTicks - targetTicks > flushAheadTicks
                        || audibleTicks >= 0L && audibleTicks - targetTicks > flushAheadTicks);
    }

    public boolean shouldFlushOutputLag(long audibleTicks, long fedTicks, long targetTicks) {
        if (!isFiniteTarget(targetTicks) || outputLagFlushTicks <= 0L || audibleTicks < 0L || fedTicks < 0L) {
            return false;
        }
        return targetTicks - audibleTicks > outputLagFlushTicks
                && targetTicks - fedTicks <= fedNearTargetTicks;
    }

    public int allowedUnits(double budget, int maxPerTick, long fedTicks, long targetTicks) {
        int max = Math.max(0, maxPerTick);
        int base = Math.min(Math.max(0, (int) budget), max);
        if (!isFiniteTarget(targetTicks) || fedTicks < 0L) {
            return base;
        }
        long behindTicks = targetTicks - fedTicks;
        if (behindTicks <= catchUpStartTicks) {
            return base;
        }
        // 保持旧 Stereo/Dolby 行为：越过起始阈值后按总 full 阈值线性提升。
        double ratio = Math.min(1.0D, behindTicks / (double) catchUpFullTicks);
        int extra = (int) Math.round((max - base) * ratio);
        return Math.max(base, Math.min(max, base + extra));
    }

    private static boolean isFiniteTarget(long targetTicks) {
        return targetTicks != Long.MAX_VALUE && targetTicks != Long.MIN_VALUE;
    }
}