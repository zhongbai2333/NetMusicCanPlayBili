package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

/** 区分已经走过空间淡变带的正常退出与需要时间包络的突发退出。 */
public final class ControlConsoleExitPolicy {
    public static final double DISCONTINUITY_DISTANCE = 2.0D;

    private ControlConsoleExitPolicy() {
    }

        public static boolean shouldFade(boolean wasActive, boolean sourceAvailable, boolean rangeChanged,
            boolean positionDiscontinuous, float previousRangeGain) {
        if (!wasActive) {
            return false;
        }
        return !sourceAvailable || rangeChanged || positionDiscontinuous || previousRangeGain >= 0.999F;
    }

    public static boolean positionDiscontinuous(double previousX, double previousY, double previousZ,
            double currentX, double currentY, double currentZ) {
        if (!Double.isFinite(previousX) || !Double.isFinite(previousY) || !Double.isFinite(previousZ)
                || !Double.isFinite(currentX) || !Double.isFinite(currentY) || !Double.isFinite(currentZ)) {
            return false;
        }
        double dx = currentX - previousX;
        double dy = currentY - previousY;
        double dz = currentZ - previousZ;
        return dx * dx + dy * dy + dz * dz > DISCONTINUITY_DISTANCE * DISCONTINUITY_DISTANCE;
    }
}