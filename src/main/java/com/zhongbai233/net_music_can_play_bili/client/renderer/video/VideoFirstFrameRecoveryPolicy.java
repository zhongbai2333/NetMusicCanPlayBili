package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/** 决定无首帧视频实例应继续等待、受控重启还是结束 Loading。 */
final class VideoFirstFrameRecoveryPolicy {
    private VideoFirstFrameRecoveryPolicy() {
    }

    static Decision decide(boolean running, boolean hasFrame, boolean restartInProgress,
            long waitingMillis, long timeoutMillis, int recoveryAttempts, int maxRecoveryAttempts) {
        if (!running || hasFrame || restartInProgress || timeoutMillis <= 0L
                || waitingMillis < timeoutMillis) {
            return Decision.WAIT;
        }
        return recoveryAttempts < Math.max(0, maxRecoveryAttempts) ? Decision.RESTART : Decision.FAIL;
    }

    enum Decision {
        WAIT,
        RESTART,
        FAIL
    }
}