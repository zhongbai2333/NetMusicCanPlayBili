package com.zhongbai233.net_music_can_play_bili.editor.core.media;

/** 不依赖 Minecraft 的中控台硬范围消费状态机。 */
public final class ControlConsoleRangeGate {
    public static final double FADE_BAND = 4.0D;
    public static final double REENTRY_INSET = 2.0D;

    private ControlConsoleRangeGate() {
    }

    public static Result evaluate(boolean previouslyActive, double relativeX, double relativeY, double relativeZ,
            double halfX, double halfY, double halfZ) {
        validateHalfExtent(halfX);
        validateHalfExtent(halfY);
        validateHalfExtent(halfZ);
        boolean inside = strictlyInside(relativeX, relativeY, relativeZ, halfX, halfY, halfZ);
        boolean active = previouslyActive ? inside : strictlyInside(relativeX, relativeY, relativeZ,
                reentryHalf(halfX), reentryHalf(halfY), reentryHalf(halfZ));
        if (!active) {
            return new Result(false, 0.0F);
        }
        double margin = Math.min(halfX - Math.abs(relativeX),
                Math.min(halfY - Math.abs(relativeY), halfZ - Math.abs(relativeZ)));
        double t = Math.clamp(margin / FADE_BAND, 0.0D, 1.0D);
        return new Result(true, (float) (t * t * (3.0D - 2.0D * t)));
    }

    private static boolean strictlyInside(double x, double y, double z, double halfX, double halfY, double halfZ) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(x) < halfX && Math.abs(y) < halfY && Math.abs(z) < halfZ;
    }

    private static double reentryHalf(double halfExtent) {
        return Math.max(1.0e-6D, halfExtent - REENTRY_INSET);
    }

    private static void validateHalfExtent(double value) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException("half extent must be finite and positive");
        }
    }

    public record Result(boolean active, float gain) {
    }
}