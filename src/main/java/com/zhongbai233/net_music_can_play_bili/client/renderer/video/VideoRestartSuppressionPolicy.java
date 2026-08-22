package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/** 视频可见性与追赶恢复的重启抑制规则。 */
final class VideoRestartSuppressionPolicy {
    private VideoRestartSuppressionPolicy() {
    }

    static boolean shouldPauseOffscreen(boolean liveSource, boolean pauseEnabled) {
        // Video decode is visual work. Live streams may need to wait for a new keyframe on
        // resume, but that is preferable to decoding indefinitely without a visible surface.
        return pauseEnabled;
    }

    static boolean shouldPauseDecodeOffscreen(boolean candidateCommitted,
            boolean liveSource, boolean pauseEnabled) {
        // A startup candidate may already own a provisional native frame whose
        // exact probe ticket still needs a caller commit/reject. Pausing the
        // consumer at that point strands the packet-drain transaction and
        // prevents the first-frame budget or fallback from terminating.
        return candidateCommitted && shouldPauseOffscreen(liveSource, pauseEnabled);
    }

    static boolean allowsRestart(boolean liveSource, boolean restartInProgress,
            long sinceDecoderStartMillis, long stabilizationMillis) {
        if (liveSource || restartInProgress) {
            return false;
        }
        return stabilizationMillis <= 0L || sinceDecoderStartMillis >= stabilizationMillis;
    }
}
