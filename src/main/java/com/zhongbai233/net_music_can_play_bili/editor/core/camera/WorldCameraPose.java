package com.zhongbai233.net_music_can_play_bili.editor.core.camera;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/** 将编辑器局部相机转换为世界位置和 Minecraft yaw/pitch 的不可变结果。 */
public final class WorldCameraPose {
    private static final float VERTICAL_EPSILON = 1.0e-6F;

    private final Vector3d position;
    private final float yawDegrees;
    private final float pitchDegrees;

    private WorldCameraPose(Vector3dc position, float yawDegrees, float pitchDegrees) {
        this.position = new Vector3d(position);
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
    }

    /**
     * 当前中控台没有 facing 属性，因此局部轴与世界轴相同；origin 是中控台底面中心。
     * 编辑器相机局部 -Z 为前方，Minecraft yaw=0 则看向世界 +Z。
     */
    public static WorldCameraPose fromLocal(Vector3dc worldOrigin, Vector3dc localPosition,
            Quaternionfc localOrientation, float verticalFallbackYawDegrees) {
        Vector3d origin = finiteVector(worldOrigin, "worldOrigin");
        Vector3d local = finiteVector(localPosition, "localPosition");
        Quaternionf orientation = new Quaternionf(java.util.Objects.requireNonNull(localOrientation,
                "localOrientation"));
        if (!Float.isFinite(orientation.x) || !Float.isFinite(orientation.y)
                || !Float.isFinite(orientation.z) || !Float.isFinite(orientation.w)
                || orientation.lengthSquared() <= 1.0e-8F) {
            throw new IllegalArgumentException("localOrientation must be finite and non-zero");
        }
        if (!Float.isFinite(verticalFallbackYawDegrees)) {
            throw new IllegalArgumentException("verticalFallbackYawDegrees must be finite");
        }
        orientation.normalize();
        Vector3f forward = orientation.transform(new Vector3f(0.0F, 0.0F, -1.0F)).normalize();
        float horizontal = (float) Math.hypot(forward.x, forward.z);
        float yaw = horizontal <= VERTICAL_EPSILON ? verticalFallbackYawDegrees
                : (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-forward.y, horizontal));
        return new WorldCameraPose(origin.add(local), yaw, pitch);
    }

    public Vector3d position() {
        return new Vector3d(position);
    }

    public float yawDegrees() {
        return yawDegrees;
    }

    public float pitchDegrees() {
        return pitchDegrees;
    }

    private static Vector3d finiteVector(Vector3dc value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y()) || !Double.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return new Vector3d(value);
    }
}