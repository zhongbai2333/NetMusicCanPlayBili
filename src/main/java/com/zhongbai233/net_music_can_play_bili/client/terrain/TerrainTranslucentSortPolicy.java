package com.zhongbai233.net_music_can_play_bili.client.terrain;

import org.joml.Matrix4fc;

/** Pure camera-space ordering boundary for persistent translucent terrain quads. */
public final class TerrainTranslucentSortPolicy {
    private TerrainTranslucentSortPolicy() {
    }

    public static boolean needsResort(Matrix4fc previous, Matrix4fc current) {
        return previous == null || !previous.equals(java.util.Objects.requireNonNull(current, "current"));
    }

    public static float viewDistanceSquared(Matrix4fc modelView,
            float sectionX, float sectionY, float sectionZ,
            float localX, float localY, float localZ) {
        java.util.Objects.requireNonNull(modelView, "modelView");
        float x = localX + sectionX;
        float y = localY + sectionY;
        float z = localZ + sectionZ;
        float viewX = modelView.m00() * x + modelView.m10() * y
                + modelView.m20() * z + modelView.m30();
        float viewY = modelView.m01() * x + modelView.m11() * y
                + modelView.m21() * z + modelView.m31();
        float viewZ = modelView.m02() * x + modelView.m12() * y
                + modelView.m22() * z + modelView.m32();
        return viewX * viewX + viewY * viewY + viewZ * viewZ;
    }
}
