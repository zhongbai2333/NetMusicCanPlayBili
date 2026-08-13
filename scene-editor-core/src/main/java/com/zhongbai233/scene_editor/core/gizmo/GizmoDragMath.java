package com.zhongbai233.scene_editor.core.gizmo;

import com.zhongbai233.scene_editor.core.projection.PickingRay;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Optional;

/** 不依赖 GUI 的轴/平面射线拖动数学。 */
public final class GizmoDragMath {
    private GizmoDragMath() {
    }

    public static Optional<Vector3d> intersectConstraint(PickingRay ray, Vector3dc origin,
            Vector3dc axisOrNormal, GizmoConstraint constraint) {
        java.util.Objects.requireNonNull(ray, "ray");
        java.util.Objects.requireNonNull(origin, "origin");
        java.util.Objects.requireNonNull(axisOrNormal, "axisOrNormal");
        java.util.Objects.requireNonNull(constraint, "constraint");
        Vector3d axis = normalized(axisOrNormal);
        return switch (constraint) {
            case X_AXIS, Y_AXIS, Z_AXIS -> intersectAxis(ray, origin, axis);
            case XY_PLANE, XZ_PLANE, YZ_PLANE, VIEW_PLANE -> intersectPlane(ray, origin, axis);
        };
    }

    public static double signedAxisDelta(Vector3dc axis, Vector3dc before, Vector3dc after) {
        return new Vector3d(after).sub(before).dot(normalized(axis));
    }

    public static float rotationDeltaDegrees(Vector3dc center, Vector3dc axis, Vector3dc before,
            Vector3dc after) {
        Vector3d normalizedAxis = normalized(axis);
        Vector3d beforeVector = projected(center, normalizedAxis, before);
        Vector3d afterVector = projected(center, normalizedAxis, after);
        double angle = Math.atan2(normalizedAxis.dot(new Vector3d(beforeVector).cross(afterVector)),
                beforeVector.dot(afterVector));
        return (float) Math.toDegrees(angle);
    }

    private static Optional<Vector3d> intersectAxis(PickingRay ray, Vector3dc origin, Vector3dc axis) {
        Vector3d delta = new Vector3d(origin).sub(ray.origin());
        Vector3d cross = new Vector3d(ray.direction()).cross(axis);
        double denominator = cross.lengthSquared();
        if (denominator <= 1.0e-12D) {
            return Optional.empty();
        }
        double distance = delta.cross(axis, new Vector3d()).dot(cross) / denominator;
        if (!Double.isFinite(distance) || distance < 0.0D) {
            return Optional.empty();
        }
        Vector3d point = ray.pointAt(distance);
        double axisParameter = new Vector3d(point).sub(origin).dot(axis);
        return Optional.of(new Vector3d(origin).fma(axisParameter, axis));
    }

    private static Optional<Vector3d> intersectPlane(PickingRay ray, Vector3dc origin, Vector3dc normal) {
        double denominator = normal.dot(ray.direction());
        if (Math.abs(denominator) <= 1.0e-9D) {
            return Optional.empty();
        }
        double distance = normal.dot(new Vector3d(origin).sub(ray.origin())) / denominator;
        return distance >= 0.0D && Double.isFinite(distance)
                ? Optional.of(ray.pointAt(distance)) : Optional.empty();
    }

    private static Vector3d projected(Vector3dc center, Vector3dc axis, Vector3dc point) {
        Vector3d result = new Vector3d(point).sub(center);
        result.fma(-result.dot(axis), axis);
        if (result.lengthSquared() <= 1.0e-12D) {
            throw new IllegalArgumentException("rotation point must not be on the rotation axis");
        }
        return result.normalize();
    }

    private static Vector3d normalized(Vector3dc value) {
        Vector3d result = new Vector3d(value);
        if (!Double.isFinite(result.x) || !Double.isFinite(result.y) || !Double.isFinite(result.z)
                || result.lengthSquared() <= 1.0e-12D) {
            throw new IllegalArgumentException("gizmo vector must be finite and non-zero");
        }
        return result.normalize();
    }
}