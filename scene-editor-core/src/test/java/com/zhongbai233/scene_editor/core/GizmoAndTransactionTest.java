package com.zhongbai233.scene_editor.core;

import com.zhongbai233.scene_editor.core.command.CommandStack;
import com.zhongbai233.scene_editor.core.gizmo.GizmoConstraint;
import com.zhongbai233.scene_editor.core.gizmo.GizmoDragMath;
import com.zhongbai233.scene_editor.core.gizmo.GizmoCoordinateSpace;
import com.zhongbai233.scene_editor.core.gizmo.GizmoTransformMath;
import com.zhongbai233.scene_editor.core.math.EditorTransform;
import com.zhongbai233.scene_editor.core.projection.PickingRay;
import com.zhongbai233.scene_editor.core.transaction.DragTransaction;
import org.joml.Vector3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
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
    void worldTranslationAxisIgnoresElementLocalRotation() {
        Vector3d worldX = new Vector3d(1.0D, 0.0D, 0.0D);
        Vector3d rotatedLocalX = new Vector3d(0.0D, 0.0D, -1.0D);
        Vector3d before = new Vector3d(2.0D, 3.0D, 4.0D);
        Vector3d after = new Vector3d(5.0D, 3.0D, 4.0D);

        assertEquals(3.0D, GizmoDragMath.signedAxisDelta(worldX, before, after), 1.0e-9D);
        assertEquals(0.0D, GizmoDragMath.signedAxisDelta(rotatedLocalX, before, after), 1.0e-9D);
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

    @Test
    void transformedRectanglePickingSupportsScalePivotAndSkew() {
        Matrix4f shear = new Matrix4f();
        shear.m10(0.5F);
        Matrix4f transform = new Matrix4f().translate(1.0F, 1.0F, 0.0F)
                .translate(0.5F, 0.0F, 0.0F)
                .mul(shear)
                .scale(2.0F, 1.0F, 1.0F)
                .translate(-0.5F, 0.0F, 0.0F);
        org.joml.Vector3f expectedWorld = transform.transformPosition(new org.joml.Vector3f(0.5F, 0.0F, 0.0F));
        PickingRay ray = new PickingRay(new Vector3d(expectedWorld.x, expectedWorld.y, 5.0D),
                new Vector3d(0.0D, 0.0D, -1.0D));
        var hit = ray.intersectTransformedRectangle(transform, 0.5D, 0.5D).orElseThrow();
        assertEquals(5.0D, hit.distance(), 1.0e-9D);
        assertEquals(0.5D, hit.localX(), 1.0e-6D);
    }

    @Test
    void localAndWorldRotationUseDifferentQuaternionCompositionOrder() {
        EditorTransform start = new EditorTransform(new Vector3f(),
                new Quaternionf().rotateY((float) Math.toRadians(90.0D)), new Vector3f(1.0F),
                new Vector3f(), 0.0F, 0.0F);
        Vector3f localForward = GizmoTransformMath.rotate(start, 0, 90.0F, GizmoCoordinateSpace.LOCAL)
                .rotation().transform(new Vector3f(0.0F, 0.0F, 1.0F));
        Vector3f worldForward = GizmoTransformMath.rotate(start, 0, 90.0F, GizmoCoordinateSpace.WORLD)
                .rotation().transform(new Vector3f(0.0F, 0.0F, 1.0F));
        assertEquals(0.0F, localForward.x, 1.0e-5F);
        assertEquals(1.0F, worldForward.x, 1.0e-5F);
    }

    @Test
    void worldScaleDecompositionPreservesTransformedScreenPlane() {
        EditorTransform start = EditorTransform.fromEulerDegrees(new Vector3f(), 35.0F, 20.0F, -10.0F,
                new Vector3f(1.3F, 0.8F, 1.0F), new Vector3f(0.25F, -0.1F, 0.0F), 0.2F, 0.0F);
        EditorTransform scaled = GizmoTransformMath.scale(start, 0, 1.4F, GizmoCoordinateSpace.WORLD,
                0.05F, 16.0F, 1.0F).orElseThrow();
        Matrix4f expectedWorldScale = new Matrix4f().translate(0.25F, -0.1F, 0.0F)
                .scale(1.4F, 1.0F, 1.0F).translate(-0.25F, 0.1F, 0.0F).mul(start.matrix());
        for (Vector3f local : new Vector3f[] {
                new Vector3f(-0.5F, -0.5F, 0.0F), new Vector3f(0.5F, -0.5F, 0.0F),
                new Vector3f(-0.5F, 0.5F, 0.0F), new Vector3f(0.5F, 0.5F, 0.0F) }) {
            Vector3f expected = expectedWorldScale.transformPosition(new Vector3f(local));
            Vector3f actual = scaled.matrix().transformPosition(new Vector3f(local));
            assertEquals(expected.x, actual.x, 1.0e-4F);
            assertEquals(expected.y, actual.y, 1.0e-4F);
            assertEquals(expected.z, actual.z, 1.0e-4F);
        }
    }
}
