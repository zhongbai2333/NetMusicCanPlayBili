package com.zhongbai233.net_music_can_play_bili.client.renderer;

/** Pure Java geometry policy underlying Minecraft projector bounds. */
final class ProjectorScreenGeometry {
    static final double BASE_HEIGHT = 1.35D;

    private ProjectorScreenGeometry() {
    }

    static Bounds aroundCenter(double centerX, double centerY, double centerZ,
            double yawDegrees, double pitchDegrees, double scale, double aspect, double margin) {
        double halfHeight = BASE_HEIGHT * Math.abs(scale) * 0.5D;
        double halfWidth = halfHeight * Math.max(0.0D, aspect);
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double upX = forwardX * Math.sin(pitch);
        double upY = Math.cos(pitch);
        double upZ = forwardZ * Math.sin(pitch);

        double extentX = Math.abs(rightX * halfWidth) + Math.abs(upX * halfHeight);
        double extentY = Math.abs(upY * halfHeight);
        double extentZ = Math.abs(rightZ * halfWidth) + Math.abs(upZ * halfHeight);
        double safeMargin = Math.max(0.0D, margin);
        return new Bounds(centerX - extentX - safeMargin, centerY - extentY - safeMargin,
                centerZ - extentZ - safeMargin, centerX + extentX + safeMargin,
                centerY + extentY + safeMargin, centerZ + extentZ + safeMargin);
    }

    static double distanceToSqr(Bounds bounds, double x, double y, double z) {
        double dx = axisDistance(x, bounds.minX(), bounds.maxX());
        double dy = axisDistance(y, bounds.minY(), bounds.maxY());
        double dz = axisDistance(z, bounds.minZ(), bounds.maxZ());
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        return value > max ? value - max : 0.0D;
    }

    record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        Bounds include(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            return new Bounds(Math.min(this.minX, minX), Math.min(this.minY, minY), Math.min(this.minZ, minZ),
                    Math.max(this.maxX, maxX), Math.max(this.maxY, maxY), Math.max(this.maxZ, maxZ));
        }
    }
}