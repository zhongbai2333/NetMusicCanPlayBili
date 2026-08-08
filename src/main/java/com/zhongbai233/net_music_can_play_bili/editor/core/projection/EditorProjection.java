package com.zhongbai233.net_music_can_play_bili.editor.core.projection;

import com.zhongbai233.net_music_can_play_bili.editor.core.camera.CameraMatrices;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector4f;

/** 使用同一相机矩阵完成世界点投影和 GUI 坐标反投影。 */
public final class EditorProjection {
    private EditorProjection() {
    }

    public static ProjectedPoint project(Vector3dc worldPoint, CameraMatrices matrices, EditorViewport viewport) {
        Vector4f clip = new Vector4f((float) worldPoint.x(), (float) worldPoint.y(), (float) worldPoint.z(), 1.0F)
                .mul(matrices.viewProjection());
        boolean behind = clip.w <= 0.0F;
        if (!Float.isFinite(clip.w) || Math.abs(clip.w) <= 1.0e-7F) {
            return new ProjectedPoint(Double.NaN, Double.NaN, Double.NaN, false, behind);
        }
        double ndcX = clip.x / clip.w;
        double ndcY = clip.y / clip.w;
        double ndcZ = clip.z / clip.w;
        double screenX = viewport.x() + (ndcX + 1.0D) * 0.5D * viewport.width();
        double screenY = viewport.y() + (1.0D - ndcY) * 0.5D * viewport.height();
        boolean visible = !behind && ndcX >= -1.0D && ndcX <= 1.0D && ndcY >= -1.0D && ndcY <= 1.0D
                && ndcZ >= -1.0D && ndcZ <= 1.0D;
        return new ProjectedPoint(screenX, screenY, ndcZ, visible, behind);
    }

    public static PickingRay rayFromScreen(double mouseX, double mouseY, CameraMatrices matrices,
            EditorViewport viewport) {
        double ndcX = ((mouseX - viewport.x()) / viewport.width()) * 2.0D - 1.0D;
        double ndcY = 1.0D - ((mouseY - viewport.y()) / viewport.height()) * 2.0D;
        Vector3d near = unproject(ndcX, ndcY, -1.0D, matrices);
        // 不反投影精确远平面：当 far/near 比极大时，float 投影矩阵会把 NDC z=1
        // 退化到齐次 w=0。视锥内部点与近点定义完全相同的透视/正交拾取方向。
        Vector3d interior = unproject(ndcX, ndcY, 0.0D, matrices);
        return new PickingRay(near, interior.sub(near));
    }

    private static Vector3d unproject(double x, double y, double z, CameraMatrices matrices) {
        Vector4f world = new Vector4f((float) x, (float) y, (float) z, 1.0F)
                .mul(matrices.inverseViewProjection());
        if (!Float.isFinite(world.w) || Math.abs(world.w) <= 1.0e-7F) {
            throw new IllegalStateException("camera matrix cannot unproject this point");
        }
        return new Vector3d(world.x / world.w, world.y / world.w, world.z / world.w);
    }
}