package com.zhongbai233.net_music_can_play_bili.media.sync;

/**
 * Constant-cost movement trend predictor. It projects one bounded movement segment and never
 * scans blocks, chunks, or paths.
 */
public final class PlaybackApproachPredictor {
    public static final double HORIZON_TICKS = 40.0D;
    public static final double MAX_LEAD_BLOCKS = 48.0D;
    public static final double MIN_SPEED_PER_TICK = 0.03D;

    private PlaybackApproachPredictor() {
    }

    public static boolean willEnterSphere(double x, double y, double z,
            double velocityX, double velocityY, double velocityZ,
            double targetX, double targetY, double targetZ, double radius) {
        if (!finite(x, y, z, velocityX, velocityY, velocityZ, targetX, targetY, targetZ, radius)
                || radius <= 0.0D) {
            return false;
        }
        double dx = targetX - x, dy = targetY - y, dz = targetZ - z;
        double radiusSquared = radius * radius;
        if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
            return false;
        }
        Segment segment = projectedSegment(velocityX, velocityY, velocityZ);
        if (segment == null || dx * segment.x() + dy * segment.y() + dz * segment.z() <= 0.0D) {
            return false;
        }
        double lengthSquared = segment.lengthSquared();
        double t = Math.clamp((dx * segment.x() + dy * segment.y() + dz * segment.z()) / lengthSquared,
                0.0D, 1.0D);
        double closestX = x + segment.x() * t;
        double closestY = y + segment.y() * t;
        double closestZ = z + segment.z() * t;
        double closestDx = targetX - closestX;
        double closestDy = targetY - closestY;
        double closestDz = targetZ - closestZ;
        return closestDx * closestDx + closestDy * closestDy + closestDz * closestDz <= radiusSquared;
    }

    public static boolean willEnterAabb(double x, double y, double z,
            double velocityX, double velocityY, double velocityZ,
            double centerX, double centerY, double centerZ,
            double halfX, double halfY, double halfZ) {
        if (!finite(x, y, z, velocityX, velocityY, velocityZ, centerX, centerY, centerZ,
                halfX, halfY, halfZ) || halfX <= 0.0D || halfY <= 0.0D || halfZ <= 0.0D
                || insideAabb(x, y, z, centerX, centerY, centerZ, halfX, halfY, halfZ)) {
            return false;
        }
        Segment segment = projectedSegment(velocityX, velocityY, velocityZ);
        if (segment == null) {
            return false;
        }
        double[] interval = { 0.0D, 1.0D };
        return intersectsAxis(x, segment.x(), centerX - halfX, centerX + halfX, interval)
                && intersectsAxis(y, segment.y(), centerY - halfY, centerY + halfY, interval)
                && intersectsAxis(z, segment.z(), centerZ - halfZ, centerZ + halfZ, interval);
    }

    private static Segment projectedSegment(double velocityX, double velocityY, double velocityZ) {
        double speed = Math.sqrt(velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ);
        if (!Double.isFinite(speed) || speed < MIN_SPEED_PER_TICK) {
            return null;
        }
        double travel = Math.min(MAX_LEAD_BLOCKS, speed * HORIZON_TICKS);
        double scale = travel / speed;
        return new Segment(velocityX * scale, velocityY * scale, velocityZ * scale);
    }

    private static boolean intersectsAxis(double origin, double direction, double minimum, double maximum,
            double[] interval) {
        if (Math.abs(direction) < 1.0e-9D) {
            return origin >= minimum && origin <= maximum;
        }
        double first = (minimum - origin) / direction;
        double second = (maximum - origin) / direction;
        double entry = Math.min(first, second);
        double exit = Math.max(first, second);
        interval[0] = Math.max(interval[0], entry);
        interval[1] = Math.min(interval[1], exit);
        return interval[0] <= interval[1];
    }

    private static boolean insideAabb(double x, double y, double z, double centerX, double centerY, double centerZ,
            double halfX, double halfY, double halfZ) {
        return Math.abs(x - centerX) < halfX && Math.abs(y - centerY) < halfY && Math.abs(z - centerZ) < halfZ;
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private record Segment(double x, double y, double z) {
        double lengthSquared() {
            return x * x + y * y + z * z;
        }
    }
}
