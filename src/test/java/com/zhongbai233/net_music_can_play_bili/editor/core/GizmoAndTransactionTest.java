package com.zhongbai233.net_music_can_play_bili.editor.core;

import com.zhongbai233.net_music_can_play_bili.editor.core.command.CommandStack;
import com.zhongbai233.net_music_can_play_bili.editor.core.gizmo.GizmoConstraint;
import com.zhongbai233.net_music_can_play_bili.editor.core.gizmo.GizmoDragMath;
import com.zhongbai233.net_music_can_play_bili.editor.core.projection.PickingRay;
import com.zhongbai233.net_music_can_play_bili.editor.core.transaction.DragTransaction;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GizmoAndTransactionTest {
    @Test
    void axisAndPlaneConstraintsFollowThePickingRay() {
        PickingRay ray = new PickingRay(new Vector3d(2.0D, 3.0D, 5.0D),
                new Vector3d(-0.2D, -0.3D, -1.0D));
        Vector3d axisHit = GizmoDragMath.intersectConstraint(ray, new Vector3d(),
                new Vector3d(1.0D, 0.0D, 0.0D), GizmoConstraint.X_AXIS).orElseThrow();
        assertEquals(0.0D, axisHit.y, 1.0e-5D);
        assertEquals(0.0D, axisHit.z, 1.0e-5D);

        Vector3d planeHit = GizmoDragMath.intersectConstraint(ray, new Vector3d(),
                new Vector3d(0.0D, 1.0D, 0.0D), GizmoConstraint.XZ_PLANE).orElseThrow();
        assertEquals(0.0D, planeHit.y, 1.0e-5D);
    }

    @Test
    void rotationConstraintReturnsSignedDegrees() {
        float degrees = GizmoDragMath.rotationDeltaDegrees(new Vector3d(), new Vector3d(0.0D, 1.0D, 0.0D),
                new Vector3d(1.0D, 0.0D, 0.0D), new Vector3d(0.0D, 0.0D, -1.0D));
        assertEquals(90.0F, degrees, 1.0e-5F);
    }

    @Test
    void dragTransactionCollapsesUpdatesToOneUndoStep() {
        CommandStack<Integer> stack = new CommandStack<>(8);
        DragTransaction<Integer> drag = new DragTransaction<>(0, "移动 X");
        drag.update(value -> value + 1);
        drag.update(value -> value + 2);
        assertEquals(3, drag.commit(stack));
        assertEquals(0, stack.undo(3));
        assertTrue(stack.canRedo());
        assertEquals(3, stack.redo(0));
    }
}