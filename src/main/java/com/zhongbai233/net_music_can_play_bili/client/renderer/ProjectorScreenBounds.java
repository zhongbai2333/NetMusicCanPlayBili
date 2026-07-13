package com.zhongbai233.net_music_can_play_bili.client.renderer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 视频投影面的纯几何计算，供视锥边界和距离裁剪共用。 */
public final class ProjectorScreenBounds {
    public static final double BASE_HEIGHT = 1.35D;

    private ProjectorScreenBounds() {
    }

    /**
     * 计算旋转后投影四边形的世界坐标 AABB，并包含投影仪方块本身。
     */
    public static AABB aroundBlock(BlockPos blockPos, double offsetX, double height, double offsetZ,
            double yawDegrees, double pitchDegrees, double scale, double aspect, double margin) {
        double centerX = blockPos.getX() + 0.5D + offsetX;
        double centerY = blockPos.getY() + height;
        double centerZ = blockPos.getZ() + 0.5D + offsetZ;
        AABB screen = aroundCenter(centerX, centerY, centerZ, yawDegrees, pitchDegrees, scale, aspect, margin);
        double minX = Math.min(blockPos.getX(), screen.minX);
        double minY = Math.min(blockPos.getY(), screen.minY);
        double minZ = Math.min(blockPos.getZ(), screen.minZ);
        double maxX = Math.max(blockPos.getX() + 1.0D, screen.maxX);
        double maxY = Math.max(blockPos.getY() + 1.0D, screen.maxY);
        double maxZ = Math.max(blockPos.getZ() + 1.0D, screen.maxZ);
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** 计算以给定世界坐标为中心的旋转投影四边形 AABB。 */
    public static AABB aroundCenter(double centerX, double centerY, double centerZ,
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
        return new AABB(centerX - extentX - safeMargin, centerY - extentY - safeMargin,
                centerZ - extentZ - safeMargin, centerX + extentX + safeMargin,
                centerY + extentY + safeMargin, centerZ + extentZ + safeMargin);
    }

    /** 点到 AABB 的最近距离平方；点位于边界内时为零。 */
    public static double distanceToSqr(AABB bounds, Vec3 point) {
        double dx = axisDistance(point.x, bounds.minX, bounds.maxX);
        double dy = axisDistance(point.y, bounds.minY, bounds.maxY);
        double dz = axisDistance(point.z, bounds.minZ, bounds.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        return value > max ? value - max : 0.0D;
    }
}