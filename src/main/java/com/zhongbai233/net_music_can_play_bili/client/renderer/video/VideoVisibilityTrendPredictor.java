package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/**
 * Constant-cost video prewarm predictor based on the future camera pose and the real screen AABB.
 * Unlike audio range prediction, being inside a sphere is not sufficient: movement or view rotation
 * must measurably move the screen toward the future viewport.
 */
final class VideoVisibilityTrendPredictor {
    private static final double HORIZON_TICKS = 12.0D;
    private static final double MAX_LEAD_BLOCKS = 24.0D;
    private static final double ROTATION_LEAD_TICKS = 5.0D;
    private static final double MIN_DISTANCE_IMPROVEMENT = 0.25D;
    private static final double MIN_DOT_IMPROVEMENT = 0.015D;

    private VideoVisibilityTrendPredictor() {
    }

    static boolean shouldPrewarm(double cameraX, double cameraY, double cameraZ,
            double velocityX, double velocityY, double velocityZ,
            double yaw, double pitch, double previousYaw, double previousPitch,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            double maxRenderDistance, double viewDotThreshold) {
        if (!finite(cameraX, cameraY, cameraZ, velocityX, velocityY, velocityZ,
                yaw, pitch, previousYaw, previousPitch, minX, minY, minZ, maxX, maxY, maxZ,
                maxRenderDistance, viewDotThreshold) || maxRenderDistance <= 0.0D
                || minX > maxX || minY > maxY || minZ > maxZ) {
            return false;
        }

        double speed = Math.sqrt(velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ);
        double travel = Math.min(MAX_LEAD_BLOCKS, speed * HORIZON_TICKS);
        double movementScale = speed > 1.0e-6D ? travel / speed : 0.0D;
        double futureX = cameraX + velocityX * movementScale;
        double futureY = cameraY + velocityY * movementScale;
        double futureZ = cameraZ + velocityZ * movementScale;

        double yawDelta = wrapDegrees(yaw - previousYaw);
        double pitchDelta = pitch - previousPitch;
        double futureYaw = yaw + Math.clamp(yawDelta, -12.0D, 12.0D) * ROTATION_LEAD_TICKS;
        double futurePitch = Math.clamp(pitch + Math.clamp(pitchDelta, -8.0D, 8.0D)
                * ROTATION_LEAD_TICKS, -89.0D, 89.0D);

        double currentDistance = distanceToAabb(cameraX, cameraY, cameraZ,
                minX, minY, minZ, maxX, maxY, maxZ);
        double futureDistance = distanceToAabb(futureX, futureY, futureZ,
                minX, minY, minZ, maxX, maxY, maxZ);
        if (futureDistance > maxRenderDistance) {
            return false;
        }

        double[] currentForward = forward(yaw, pitch);
        double[] futureForward = forward(futureYaw, futurePitch);
        double currentDot = dotToNearestPoint(cameraX, cameraY, cameraZ, currentForward,
                minX, minY, minZ, maxX, maxY, maxZ);
        double futureDot = dotToNearestPoint(futureX, futureY, futureZ, futureForward,
                minX, minY, minZ, maxX, maxY, maxZ);
        boolean movingToward = futureDistance + MIN_DISTANCE_IMPROVEMENT < currentDistance;
        boolean turningToward = futureDot > currentDot + MIN_DOT_IMPROVEMENT;
        return (movingToward || turningToward) && futureDot > viewDotThreshold;
    }

    private static double[] forward(double yawDegrees, double pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double cosPitch = Math.cos(pitch);
        return new double[] { -Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch };
    }

    private static double dotToNearestPoint(double x, double y, double z, double[] forward,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double targetX = Math.clamp(x, minX, maxX);
        double targetY = Math.clamp(y, minY, maxY);
        double targetZ = Math.clamp(z, minZ, maxZ);
        if (targetX == x && targetY == y && targetZ == z) {
            targetX = (minX + maxX) * 0.5D;
            targetY = (minY + maxY) * 0.5D;
            targetZ = (minZ + maxZ) * 0.5D;
        }
        double dx = targetX - x;
        double dy = targetY - y;
        double dz = targetZ - z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return length > 1.0e-6D
                ? (dx * forward[0] + dy * forward[1] + dz * forward[2]) / length : 1.0D;
    }

    private static double distanceToAabb(double x, double y, double z,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double dx = axisDistance(x, minX, maxX);
        double dy = axisDistance(y, minY, maxY);
        double dz = axisDistance(z, minZ, maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) return min - value;
        return value > max ? value - max : 0.0D;
    }

    private static double wrapDegrees(double value) {
        double wrapped = value % 360.0D;
        if (wrapped >= 180.0D) wrapped -= 360.0D;
        if (wrapped < -180.0D) wrapped += 360.0D;
        return wrapped;
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) return false;
        }
        return true;
    }
}
