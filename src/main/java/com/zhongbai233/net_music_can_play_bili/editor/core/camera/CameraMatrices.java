package com.zhongbai233.net_music_can_play_bili.editor.core.camera;

import com.zhongbai233.net_music_can_play_bili.editor.core.projection.EditorViewport;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/** 冻结的一帧相机矩阵，供渲染、投影和拾取共同使用。 */
public final class CameraMatrices {
    private final Matrix4f view;
    private final Matrix4f projection;
    private final Matrix4f viewProjection;
    private final Matrix4f inverseViewProjection;

    private CameraMatrices(Matrix4f view, Matrix4f projection) {
        this.view = new Matrix4f(view);
        this.projection = new Matrix4f(projection);
        this.viewProjection = new Matrix4f(projection).mul(view);
        this.inverseViewProjection = new Matrix4f(viewProjection).invert();
    }

    public static CameraMatrices create(EditorCameraState camera, EditorViewport viewport) {
        Vector3d position = camera.position();
        Matrix4f view = new Matrix4f()
                .rotate(camera.orientation().conjugate())
                .translate((float) -position.x, (float) -position.y, (float) -position.z);
        Matrix4f projection;
        if (camera.mode() == EditorCameraMode.ORTHOGRAPHIC) {
            float halfHeight = camera.orthoScale();
            float halfWidth = halfHeight * (float) viewport.aspectRatio();
            projection = new Matrix4f().ortho(-halfWidth, halfWidth, -halfHeight, halfHeight,
                    camera.nearPlane(), camera.farPlane());
        } else {
            projection = new Matrix4f().perspective((float) Math.toRadians(camera.fovDegrees()),
                    (float) viewport.aspectRatio(), camera.nearPlane(), camera.farPlane());
        }
        return new CameraMatrices(view, projection);
    }

    /** 从宿主已有的矩阵约定创建防御性快照，便于逐步迁移旧渲染路径。 */
    public static CameraMatrices from(Matrix4f view, Matrix4f projection) {
        java.util.Objects.requireNonNull(view, "view");
        java.util.Objects.requireNonNull(projection, "projection");
        return new CameraMatrices(view, projection);
    }

    public Matrix4f view() {
        return new Matrix4f(view);
    }

    public Matrix4f projection() {
        return new Matrix4f(projection);
    }

    public Matrix4f viewProjection() {
        return new Matrix4f(viewProjection);
    }

    public Matrix4f inverseViewProjection() {
        return new Matrix4f(inverseViewProjection);
    }
}