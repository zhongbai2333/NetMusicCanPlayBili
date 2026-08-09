package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/** 视频可见性与追赶恢复的重启抑制规则。 */
final class VideoRestartSuppressionPolicy {
    private VideoRestartSuppressionPolicy() {
    }

    static boolean shouldPauseOffscreen(boolean liveSource, boolean pauseEnabled) {
        // 直播总线是持续前进的短缓存；停止消费会丢掉时间连续性，并在每次重新可见时被迫等关键帧。
        return pauseEnabled && !liveSource;
    }

    static boolean allowsRestart(boolean liveSource, boolean restartInProgress,
            long sinceDecoderStartMillis, long stabilizationMillis) {
        if (liveSource || restartInProgress) {
            return false;
        }
        return stabilizationMillis <= 0L || sinceDecoderStartMillis >= stabilizationMillis;
    }
}