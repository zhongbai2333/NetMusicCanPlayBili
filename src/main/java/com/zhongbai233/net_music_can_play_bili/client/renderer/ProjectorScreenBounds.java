package com.zhongbai233.net_music_can_play_bili.client.renderer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 视频投影面的纯几何计算，供视锥边界和距离裁剪共用。 */
public final class ProjectorScreenBounds {
    public static final double BASE_HEIGHT = ProjectorScreenGeometry.BASE_HEIGHT;

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
        ProjectorScreenGeometry.Bounds bounds = ProjectorScreenGeometry
            .aroundCenter(centerX, centerY, centerZ, yawDegrees, pitchDegrees, scale, aspect, margin)
            .include(blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                blockPos.getX() + 1.0D, blockPos.getY() + 1.0D, blockPos.getZ() + 1.0D);
        return toAabb(bounds);
    }

    /** 计算以给定世界坐标为中心的旋转投影四边形 AABB。 */
    public static AABB aroundCenter(double centerX, double centerY, double centerZ,
            double yawDegrees, double pitchDegrees, double scale, double aspect, double margin) {
        return toAabb(ProjectorScreenGeometry.aroundCenter(
            centerX, centerY, centerZ, yawDegrees, pitchDegrees, scale, aspect, margin));
    }

    /** 点到 AABB 的最近距离平方；点位于边界内时为零。 */
    public static double distanceToSqr(AABB bounds, Vec3 point) {
        return ProjectorScreenGeometry.distanceToSqr(
                new ProjectorScreenGeometry.Bounds(bounds.minX, bounds.minY, bounds.minZ,
                        bounds.maxX, bounds.maxY, bounds.maxZ),
                point.x, point.y, point.z);
    }

    private static AABB toAabb(ProjectorScreenGeometry.Bounds bounds) {
        return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }
}