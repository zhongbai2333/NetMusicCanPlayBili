package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.util.concurrent.TimeUnit;

/** Pure dual-budget decision for a decoder candidate's first output frame. */
final class VideoFirstFrameProbeBudget {
    enum Outcome {
        WITHIN_BUDGET,
        TIME_EXHAUSTED,
        PACKET_EXHAUSTED
    }

    private VideoFirstFrameProbeBudget() {
    }

    static Outcome evaluate(long elapsedNanos, long timeoutMillis, int sentPackets, int maxPackets) {
        return evaluate(false, elapsedNanos, timeoutMillis, sentPackets, maxPackets);
    }

    static Outcome evaluate(boolean firstFrameReady, long elapsedNanos, long timeoutMillis,
            int sentPackets, int maxPackets) {
        long safeElapsedNanos = Math.max(0L, elapsedNanos);
        if (timeoutMillis > 0L
                && safeElapsedNanos >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis)) {
            return Outcome.TIME_EXHAUSTED;
        }
        if (firstFrameReady) {
            return Outcome.WITHIN_BUDGET;
        }
        if (maxPackets > 0 && Math.max(0, sentPackets) >= maxPackets) {
            return Outcome.PACKET_EXHAUSTED;
        }
        return Outcome.WITHIN_BUDGET;
    }
}
