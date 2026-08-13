package com.zhongbai233.scene_editor.core.projection;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Matrix4d;
import org.joml.Matrix4fc;

import java.util.Optional;
import java.util.OptionalDouble;

/** 世界空间拾取射线。 */
public final class PickingRay {
    private final Vector3d origin;
    private final Vector3d direction;

    public PickingRay(Vector3dc origin, Vector3dc direction) {
        this.origin = new Vector3d(origin);
        this.direction = new Vector3d(direction);
        if (this.direction.lengthSquared() <= 1.0e-12D) {
            throw new IllegalArgumentException("ray direction must be non-zero");
        }
        this.direction.normalize();
    }

    public Vector3d origin() {
        return new Vector3d(origin);
    }

    public Vector3d direction() {
        return new Vector3d(direction);
    }

    public Vector3d pointAt(double distance) {
        return new Vector3d(direction).mul(distance).add(origin);
    }

    /**
     * 与由中心、局部 X/Y 单位轴和半尺寸描述的双面有向矩形求交。
     */
    public Optional<RayRectangleIntersection> intersectRectangle(Vector3dc center, Vector3dc localXAxis,
            Vector3dc localYAxis, double halfWidth, double halfHeight) {
        java.util.Objects.requireNonNull(center, "center");
        Vector3d xAxis = normalized(localXAxis, "localXAxis");
        Vector3d yAxis = normalized(localYAxis, "localYAxis");
        if (!Double.isFinite(halfWidth) || !Double.isFinite(halfHeight) || halfWidth <= 0.0D
                || halfHeight <= 0.0D) {
            throw new IllegalArgumentException("rectangle half dimensions must be positive and finite");
        }
        Vector3d normal = xAxis.cross(yAxis, new Vector3d());
        if (normal.lengthSquared() <= 1.0e-12D) {
            throw new IllegalArgumentException("rectangle axes must not be parallel");
        }
        normal.normalize();
        double denominator = normal.dot(direction);
        if (Math.abs(denominator) <= 1.0e-9D) {
            return Optional.empty();
        }
        double distance = normal.dot(new Vector3d(center).sub(origin)) / denominator;
        if (!Double.isFinite(distance) || distance < 0.0D) {
            return Optional.empty();
        }
        Vector3d point = pointAt(distance);
        Vector3d offset = new Vector3d(point).sub(center);
        double localX = offset.dot(xAxis);
        double localY = offset.dot(yAxis);
        if (Math.abs(localX) > halfWidth + 1.0e-9D || Math.abs(localY) > halfHeight + 1.0e-9D) {
            return Optional.empty();
        }
        return Optional.of(new RayRectangleIntersection(distance, point, localX, localY));
    }

    /**
     * 与完整仿射变换后的局部 XY 矩形求交。逆变换直接作用于射线，因而支持非均匀缩放、
     * pivot 和 skew；返回距离仍是原始单位世界射线的参数。
     */
    public Optional<RayRectangleIntersection> intersectTransformedRectangle(Matrix4fc localToWorld,
            double halfWidth, double halfHeight) {
        java.util.Objects.requireNonNull(localToWorld, "localToWorld");
        if (!Double.isFinite(halfWidth) || !Double.isFinite(halfHeight)
                || halfWidth <= 0.0D || halfHeight <= 0.0D) {
            throw new IllegalArgumentException("rectangle half dimensions must be positive and finite");
        }
        Matrix4d inverse = new Matrix4d(localToWorld);
        if (!Double.isFinite(inverse.determinant()) || Math.abs(inverse.determinant()) <= 1.0e-12D) {
            return Optional.empty();
        }
        inverse.invert();
        Vector3d localOrigin = inverse.transformPosition(new Vector3d(origin));
        Vector3d localDirection = inverse.transformDirection(new Vector3d(direction));
        if (Math.abs(localDirection.z) <= 1.0e-12D) {
            return Optional.empty();
        }
        double distance = -localOrigin.z / localDirection.z;
        if (!Double.isFinite(distance) || distance < 0.0D) {
            return Optional.empty();
        }
        double localX = localOrigin.x + localDirection.x * distance;
        double localY = localOrigin.y + localDirection.y * distance;
        if (Math.abs(localX) > halfWidth + 1.0e-9D || Math.abs(localY) > halfHeight + 1.0e-9D) {
            return Optional.empty();
        }
        return Optional.of(new RayRectangleIntersection(distance, pointAt(distance), localX, localY));
    }

    /**
     * 与世界空间轴对齐包围盒求交，返回沿射线的最近非负距离。射线起点位于盒内时返回 0。
     */
    public OptionalDouble intersectAabb(Vector3dc minimum, Vector3dc maximum) {
        Vector3d min = finiteCopy(minimum, "minimum");
        Vector3d max = finiteCopy(maximum, "maximum");
        if (min.x > max.x || min.y > max.y || min.z > max.z) {
            throw new IllegalArgumentException("AABB minimum must not exceed maximum");
        }

        double near = 0.0D;
        double far = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            double axisOrigin = origin.get(axis);
            double axisDirection = direction.get(axis);
            double axisMinimum = min.get(axis);
            double axisMaximum = max.get(axis);
            if (Math.abs(axisDirection) <= 1.0e-12D) {
                if (axisOrigin < axisMinimum || axisOrigin > axisMaximum) {
                    return OptionalDouble.empty();
                }
                continue;
            }
            double first = (axisMinimum - axisOrigin) / axisDirection;
            double second = (axisMaximum - axisOrigin) / axisDirection;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            near = Math.max(near, first);
            far = Math.min(far, second);
            if (near > far) {
                return OptionalDouble.empty();
            }
        }
        return far >= 0.0D ? OptionalDouble.of(near) : OptionalDouble.empty();
    }

    private static Vector3d normalized(Vector3dc value, String name) {
        java.util.Objects.requireNonNull(value, name);
        Vector3d result = new Vector3d(value);
        if (!Double.isFinite(result.x) || !Double.isFinite(result.y) || !Double.isFinite(result.z)
                || result.lengthSquared() <= 1.0e-12D) {
            throw new IllegalArgumentException(name + " must be finite and non-zero");
        }
        return result.normalize();
    }

    private static Vector3d finiteCopy(Vector3dc value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y()) || !Double.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return new Vector3d(value);
    }
}
