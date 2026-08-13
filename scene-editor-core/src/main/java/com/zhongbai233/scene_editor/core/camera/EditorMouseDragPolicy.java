package com.zhongbai233.scene_editor.core.camera;

/** 编辑器预览区的鼠标拖拽职责映射。 */
public final class EditorMouseDragPolicy {
    private EditorMouseDragPolicy() {
    }

    public static Action action(int mouseButton, boolean firstPerson, boolean gizmoHandleActive) {
        if (mouseButton == 1 && !firstPerson) {
            return Action.PAN;
        }
        if (mouseButton == 0 && gizmoHandleActive && !firstPerson) {
            return Action.GIZMO;
        }
        return Action.ORBIT;
    }

    public enum Action {
        ORBIT,
        PAN,
        GIZMO
    }
}