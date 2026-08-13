package com.zhongbai233.scene_editor.core.camera;

import com.zhongbai233.scene_editor.core.projection.EditorViewport;

/** 一次视口提取所使用的不可变相机快照；渲染、投影和拾取必须共享同一实例。 */
public record CameraFrame(CameraMatrices matrices, EditorViewport viewport, EditorCameraMode mode) {
    public CameraFrame {
        java.util.Objects.requireNonNull(matrices, "matrices");
        java.util.Objects.requireNonNull(viewport, "viewport");
        java.util.Objects.requireNonNull(mode, "mode");
    }
}