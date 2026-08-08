package com.zhongbai233.net_music_can_play_bili.editor.core.projection;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/** 射线命中有向矩形后的世界坐标、局部坐标和沿射线距离。 */
public record RayRectangleIntersection(double distance, Vector3dc worldPoint, double localX, double localY) {
    public RayRectangleIntersection {
        if (!Double.isFinite(distance) || distance < 0.0D || !Double.isFinite(localX)
                || !Double.isFinite(localY)) {
            throw new IllegalArgumentException("ray rectangle intersection values must be finite and non-negative");
        }
        worldPoint = new Vector3d(java.util.Objects.requireNonNull(worldPoint, "worldPoint"));
    }

    @Override
    public Vector3dc worldPoint() {
        return new Vector3d(worldPoint);
    }
}