package com.zhongbai233.net_music_can_play_bili.editor.core.camera;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * 不可变的编辑器相机状态。姿态表示相机局部坐标到世界坐标的旋转，局部 -Z 为前方。
 */
public final class EditorCameraState {
    private final EditorCameraMode mode;
    private final Vector3d position;
    private final Quaternionf orientation;
    private final Vector3d focus;
    private final float fovDegrees;
    private final float orthoScale;
    private final float nearPlane;
    private final float farPlane;

    public EditorCameraState(EditorCameraMode mode, Vector3dc position, Quaternionfc orientation, Vector3dc focus,
            float fovDegrees, float orthoScale, float nearPlane, float farPlane) {
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
        this.position = finiteCopy(position, "position");
        this.focus = finiteCopy(focus, "focus");
        this.orientation = new Quaternionf(java.util.Objects.requireNonNull(orientation, "orientation"));
        if (!Float.isFinite(this.orientation.x) || !Float.isFinite(this.orientation.y)
            || !Float.isFinite(this.orientation.z) || !Float.isFinite(this.orientation.w)
            || this.orientation.lengthSquared() <= 1.0e-8F) {
            throw new IllegalArgumentException("orientation must be finite and non-zero");
        }
        this.orientation.normalize();
        if (!Float.isFinite(fovDegrees) || fovDegrees <= 1.0F || fovDegrees >= 179.0F) {
            throw new IllegalArgumentException("fovDegrees must be within (1, 179)");
        }
        if (!Float.isFinite(orthoScale) || orthoScale <= 0.0F) {
            throw new IllegalArgumentException("orthoScale must be positive");
        }
        if (!Float.isFinite(nearPlane) || !Float.isFinite(farPlane) || nearPlane <= 0.0F
                || farPlane <= nearPlane) {
            throw new IllegalArgumentException("camera clipping planes are invalid");
        }
        this.fovDegrees = fovDegrees;
        this.orthoScale = orthoScale;
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
    }

    public static EditorCameraState lookingAt(EditorCameraMode mode, Vector3dc position, Vector3dc focus,
            Vector3dc worldUp, float fovDegrees, float orthoScale, float nearPlane, float farPlane) {
        Vector3d direction = new Vector3d(focus).sub(position);
        if (direction.lengthSquared() <= 1.0e-12D) {
            throw new IllegalArgumentException("camera position and focus must differ");
        }
        Vector3f directionF = new Vector3f((float) direction.x, (float) direction.y, (float) direction.z).normalize();
        Vector3f upF = new Vector3f((float) worldUp.x(), (float) worldUp.y(), (float) worldUp.z()).normalize();
        Quaternionf orientation = new Quaternionf().lookAlong(directionF, upF).conjugate();
        return new EditorCameraState(mode, position, orientation, focus, fovDegrees, orthoScale, nearPlane, farPlane);
    }

    public EditorCameraMode mode() {
        return mode;
    }

    public Vector3d position() {
        return new Vector3d(position);
    }

    public Quaternionf orientation() {
        return new Quaternionf(orientation);
    }

    public Vector3d focus() {
        return new Vector3d(focus);
    }

    public float fovDegrees() {
        return fovDegrees;
    }

    public float orthoScale() {
        return orthoScale;
    }

    public float nearPlane() {
        return nearPlane;
    }

    public float farPlane() {
        return farPlane;
    }

    public EditorCameraState withPose(Vector3dc newPosition, Quaternionfc newOrientation, Vector3dc newFocus) {
        return new EditorCameraState(mode, newPosition, newOrientation, newFocus, fovDegrees, orthoScale, nearPlane,
                farPlane);
    }

    public EditorCameraState withMode(EditorCameraMode newMode) {
        return new EditorCameraState(newMode, position, orientation, focus, fovDegrees, orthoScale, nearPlane,
                farPlane);
    }

    public EditorCameraState withOrthoScale(float newScale) {
        return new EditorCameraState(mode, position, orientation, focus, fovDegrees, newScale, nearPlane, farPlane);
    }

    private static Vector3d finiteCopy(Vector3dc value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y()) || !Double.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return new Vector3d(value);
    }
}