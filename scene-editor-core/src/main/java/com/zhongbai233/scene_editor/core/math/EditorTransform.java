package com.zhongbai233.scene_editor.core.math;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** 不可变场景变换，矩阵顺序为 T(position) T(pivot) R H(skew) S T(-pivot)。 */
public final class EditorTransform {
    private final Vector3f position;
    private final Quaternionf rotation;
    private final Vector3f scale;
    private final Vector3f pivot;
    private final float skewXByY;
    private final float skewYByX;

    public EditorTransform(Vector3fc position, Quaternionfc rotation, Vector3fc scale, Vector3fc pivot,
            float skewXByY, float skewYByX) {
        this.position = finiteCopy(position, "position");
        this.scale = finiteCopy(scale, "scale");
        this.pivot = finiteCopy(pivot, "pivot");
        this.rotation = new Quaternionf(java.util.Objects.requireNonNull(rotation, "rotation"));
        if (!Float.isFinite(this.rotation.x) || !Float.isFinite(this.rotation.y)
                || !Float.isFinite(this.rotation.z) || !Float.isFinite(this.rotation.w)
                || this.rotation.lengthSquared() <= 1.0e-8F) {
            throw new IllegalArgumentException("rotation must be finite and non-zero");
        }
        this.rotation.normalize();
        if (this.scale.x <= 0.0F || this.scale.y <= 0.0F || this.scale.z <= 0.0F) {
            throw new IllegalArgumentException("scale components must be positive");
        }
        if (!Float.isFinite(skewXByY) || !Float.isFinite(skewYByX)) {
            throw new IllegalArgumentException("skew must be finite");
        }
        this.skewXByY = skewXByY;
        this.skewYByX = skewYByX;
    }

    public static EditorTransform identity() {
        return new EditorTransform(new Vector3f(), new Quaternionf(), new Vector3f(1.0F), new Vector3f(), 0.0F,
                0.0F);
    }

    /** 以 yaw(Y)、pitch(X)、roll(Z) 顺序建立元素姿态，角度单位为度。 */
    public static EditorTransform fromEulerDegrees(Vector3fc position, float yaw, float pitch, float roll,
            Vector3fc scale, Vector3fc pivot, float skewXByY, float skewYByX) {
        Quaternionf rotation = new Quaternionf().rotateYXZ((float) Math.toRadians(yaw),
                (float) Math.toRadians(pitch), (float) Math.toRadians(roll));
        return new EditorTransform(position, rotation, scale, pivot, skewXByY, skewYByX);
    }

    public Vector3f position() {
        return new Vector3f(position);
    }

    public Quaternionf rotation() {
        return new Quaternionf(rotation);
    }

    public Vector3f scale() {
        return new Vector3f(scale);
    }

    public Vector3f pivot() {
        return new Vector3f(pivot);
    }

    public float skewXByY() {
        return skewXByY;
    }

    public float skewYByX() {
        return skewYByX;
    }

    public Matrix4f matrix() {
        Matrix4f shear = new Matrix4f().identity();
        shear.m10(skewXByY);
        shear.m01(skewYByX);
        return new Matrix4f().translate(position).translate(pivot).rotate(rotation).mul(shear).scale(scale)
                .translate(-pivot.x, -pivot.y, -pivot.z);
    }

    public EditorTransform withPosition(Vector3fc newPosition) {
        return new EditorTransform(newPosition, rotation, scale, pivot, skewXByY, skewYByX);
    }

    public EditorTransform withRotation(Quaternionfc newRotation) {
        return new EditorTransform(position, newRotation, scale, pivot, skewXByY, skewYByX);
    }

    public EditorTransform withEulerDegrees(float yaw, float pitch, float roll) {
        return withRotation(new Quaternionf().rotateYXZ((float) Math.toRadians(yaw),
                (float) Math.toRadians(pitch), (float) Math.toRadians(roll)));
    }

    public EditorTransform withScale(Vector3fc newScale) {
        return new EditorTransform(position, rotation, newScale, pivot, skewXByY, skewYByX);
    }

    public EditorTransform withPivot(Vector3fc newPivot) {
        return new EditorTransform(position, rotation, scale, newPivot, skewXByY, skewYByX);
    }

    public EditorTransform withSkew(float newSkewXByY, float newSkewYByX) {
        return new EditorTransform(position, rotation, scale, pivot, newSkewXByY, newSkewYByX);
    }

    private static Vector3f finiteCopy(Vector3fc value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return new Vector3f(value);
    }
}