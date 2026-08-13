package com.zhongbai233.scene_editor.core;

import com.zhongbai233.scene_editor.core.camera.EditorMouseDragPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EditorMouseDragPolicyTest {
    @Test
    void modelingViewUsesLeftOrbitAndRightPanWithoutSelection() {
        assertEquals(EditorMouseDragPolicy.Action.ORBIT,
                EditorMouseDragPolicy.action(0, false, false));
        assertEquals(EditorMouseDragPolicy.Action.PAN,
                EditorMouseDragPolicy.action(1, false, false));
    }

    @Test
    void leftDragUsesGizmoOnlyWhenAHandleIsActive() {
        assertEquals(EditorMouseDragPolicy.Action.GIZMO,
                EditorMouseDragPolicy.action(0, false, true));
        assertEquals(EditorMouseDragPolicy.Action.PAN,
                EditorMouseDragPolicy.action(1, false, true));
    }

    @Test
    void firstPersonKeepsMouseLookInsteadOfPanning() {
        assertEquals(EditorMouseDragPolicy.Action.ORBIT,
                EditorMouseDragPolicy.action(1, true, false));
    }
}