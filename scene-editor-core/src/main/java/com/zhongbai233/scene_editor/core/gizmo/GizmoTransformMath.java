package com.zhongbai233.scene_editor.core.gizmo;

import com.zhongbai233.scene_editor.core.math.EditorTransform;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Optional;

/** 完整 Transform 的本地/世界旋转与非均匀缩放数学。 */
public final class GizmoTransformMath {
    private static final float EPSILON = 1.0e-6F;

    private GizmoTransformMath() {
    }

    public static EditorTransform rotate(EditorTransform start, int axisIndex, float deltaDegrees,
            GizmoCoordinateSpace space) {
        java.util.Objects.requireNonNull(start, "start");
        java.util.Objects.requireNonNull(space, "space");
        Vector3f axis = canonicalAxis(axisIndex);
        Quaternionf delta = new Quaternionf().fromAxisAngleRad(axis,
                (float) Math.toRadians(deltaDegrees));
        Quaternionf rotation = space == GizmoCoordinateSpace.WORLD
                ? delta.mul(start.rotation(), new Quaternionf())
                : start.rotation().mul(delta, new Quaternionf());
        return start.withRotation(rotation);
    }

    /**
     * 沿 Gizmo 轴缩放。世界空间通过对屏幕平面的两个仿射基向量做 QR 分解，精确保留其
     * 世界几何；若结果超出文档 scale/skew 域则拒绝本次更新。
     */
    public static Optional<EditorTransform> scale(EditorTransform start, int axisIndex, float factor,
            GizmoCoordinateSpace space, float minScale, float maxScale, float maxAbsSkew) {
        java.util.Objects.requireNonNull(start, "start");
        java.util.Objects.requireNonNull(space, "space");
        if (!Float.isFinite(factor) || factor <= 0.0F) {
            return Optional.empty();
        }
        if (space == GizmoCoordinateSpace.LOCAL) {
            Vector3f scale = start.scale();
            scale.setComponent(axisIndex, scale.get(axisIndex) * factor);
            return valid(scale, start.skewXByY(), start.skewYByX(), minScale, maxScale, maxAbsSkew)
                    ? Optional.of(start.withScale(scale)) : Optional.empty();
        }

        Vector3f axis = canonicalAxis(axisIndex);
        Matrix3f worldScale = new Matrix3f().identity();
        float amount = factor - 1.0F;
        worldScale.m00(worldScale.m00() + amount * axis.x * axis.x);
        worldScale.m01(worldScale.m01() + amount * axis.x * axis.y);
        worldScale.m02(worldScale.m02() + amount * axis.x * axis.z);
        worldScale.m10(worldScale.m10() + amount * axis.y * axis.x);
        worldScale.m11(worldScale.m11() + amount * axis.y * axis.y);
        worldScale.m12(worldScale.m12() + amount * axis.y * axis.z);
        worldScale.m20(worldScale.m20() + amount * axis.z * axis.x);
        worldScale.m21(worldScale.m21() + amount * axis.z * axis.y);
        worldScale.m22(worldScale.m22() + amount * axis.z * axis.z);

        Matrix3f linear = start.matrix().get3x3(new Matrix3f());
        worldScale.mul(linear, linear);
        Vector3f xColumn = linear.getColumn(0, new Vector3f());
        Vector3f yColumn = linear.getColumn(1, new Vector3f());
        Vector3f zColumn = linear.getColumn(2, new Vector3f());
        float scaleX = xColumn.length();
        if (!Float.isFinite(scaleX) || scaleX <= EPSILON) {
            return Optional.empty();
        }
        Vector3f xAxis = xColumn.div(scaleX, new Vector3f());
        float projection = yColumn.dot(xAxis);
        Vector3f yOrthogonal = new Vector3f(yColumn).fma(-projection, xAxis);
        float scaleY = yOrthogonal.length();
        if (!Float.isFinite(scaleY) || scaleY <= EPSILON) {
            return Optional.empty();
        }
        Vector3f yAxis = yOrthogonal.div(scaleY, new Vector3f());
        Vector3f zAxis = xAxis.cross(yAxis, new Vector3f()).normalize();
        float scaleZ = Math.abs(zColumn.dot(zAxis));
        float skewXByY = projection / scaleY;
        Vector3f scales = new Vector3f(scaleX, scaleY, scaleZ);
        if (!valid(scales, skewXByY, 0.0F, minScale, maxScale, maxAbsSkew)) {
            return Optional.empty();
        }
        Matrix3f rotationMatrix = new Matrix3f()
                .setColumn(0, xAxis).setColumn(1, yAxis).setColumn(2, zAxis);
        Quaternionf rotation = rotationMatrix.getNormalizedRotation(new Quaternionf()).normalize();
        return Optional.of(new EditorTransform(start.position(), rotation, scales, start.pivot(),
                skewXByY, 0.0F));
    }

    private static boolean valid(Vector3f scale, float skewXByY, float skewYByX,
            float minScale, float maxScale, float maxAbsSkew) {
        return Float.isFinite(scale.x) && Float.isFinite(scale.y) && Float.isFinite(scale.z)
                && scale.x >= minScale && scale.x <= maxScale
                && scale.y >= minScale && scale.y <= maxScale
                && scale.z >= minScale && scale.z <= maxScale
                && Float.isFinite(skewXByY) && Math.abs(skewXByY) <= maxAbsSkew
                && Float.isFinite(skewYByX) && Math.abs(skewYByX) <= maxAbsSkew;
    }

    private static Vector3f canonicalAxis(int axisIndex) {
        return switch (axisIndex) {
            case 0 -> new Vector3f(1.0F, 0.0F, 0.0F);
            case 1 -> new Vector3f(0.0F, 1.0F, 0.0F);
            case 2 -> new Vector3f(0.0F, 0.0F, 1.0F);
            default -> throw new IllegalArgumentException("axisIndex must be 0, 1 or 2");
        };
    }
}
