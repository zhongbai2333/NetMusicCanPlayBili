package com.zhongbai233.scene_editor.core;

import com.zhongbai233.scene_editor.core.camera.CameraMatrices;
import com.zhongbai233.scene_editor.core.camera.EditorCameraController;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.projection.EditorProjection;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import com.zhongbai233.scene_editor.core.projection.PickingRay;
import com.zhongbai233.scene_editor.core.projection.ProjectedPoint;
import org.joml.Vector3d;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraProjectionTest {
    private static final double EPSILON = 1.0e-5D;

    @Test
    void projectsFocusToViewportCenterAndBuildsForwardRay() {
        EditorCameraState camera = EditorCameraState.lookingAt(EditorCameraMode.ORBIT,
                new Vector3d(0.0D, 1.0D, 6.0D), new Vector3d(0.0D, 1.0D, 0.0D),
                new Vector3d(0.0D, 1.0D, 0.0D), 45.0F, 4.0F, 0.05F, 100.0F);
        EditorViewport viewport = new EditorViewport(10, 20, 800, 400);
        CameraMatrices matrices = CameraMatrices.create(camera, viewport);

        ProjectedPoint focus = EditorProjection.project(camera.focus(), matrices, viewport);
        assertTrue(focus.visible());
        assertFalse(focus.behindCamera());
        assertEquals(410.0D, focus.screenX(), EPSILON);
        assertEquals(220.0D, focus.screenY(), EPSILON);

        PickingRay ray = EditorProjection.rayFromScreen(410.0D, 220.0D, matrices, viewport);
        assertEquals(0.0D, ray.direction().x, EPSILON);
        assertEquals(0.0D, ray.direction().y, EPSILON);
        assertEquals(-1.0D, ray.direction().z, EPSILON);
    }

        @Test
        void pickingRayRemainsFiniteWithMillionBlockFarPlane() {
                EditorCameraState camera = EditorCameraState.lookingAt(EditorCameraMode.ORBIT,
                                new Vector3d(0.0D, 1.0D, 256.0D), new Vector3d(0.0D, 1.0D, 0.0D),
                                new Vector3d(0.0D, 1.0D, 0.0D), 45.0F, 4.0F, 0.05F, 1.0e6F);
                EditorViewport viewport = new EditorViewport(10, 20, 800, 400);
                CameraMatrices matrices = CameraMatrices.create(camera, viewport);

                PickingRay center = EditorProjection.rayFromScreen(410.0D, 220.0D, matrices, viewport);
                assertTrue(Double.isFinite(center.origin().x));
                assertTrue(Double.isFinite(center.origin().y));
                assertTrue(Double.isFinite(center.origin().z));
                assertEquals(0.0D, center.direction().x, EPSILON);
                assertEquals(0.0D, center.direction().y, EPSILON);
                assertEquals(-1.0D, center.direction().z, EPSILON);

                PickingRay corner = EditorProjection.rayFromScreen(10.0D, 20.0D, matrices, viewport);
                assertTrue(Double.isFinite(corner.direction().x));
                assertTrue(Double.isFinite(corner.direction().y));
                assertTrue(Double.isFinite(corner.direction().z));
        }

    @Test
    void rejectsInvalidCameraAndViewportValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new EditorViewport(0, 0, 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> EditorCameraState.lookingAt(EditorCameraMode.ORBIT, new Vector3d(), new Vector3d(),
                        new Vector3d(0.0D, 1.0D, 0.0D), 45.0F, 4.0F, 0.05F, 100.0F));
        assertThrows(IllegalArgumentException.class,
                () -> EditorCameraState.lookingAt(EditorCameraMode.ORBIT,
                        new Vector3d(0.0D, 0.0D, 5.0D), new Vector3d(), new Vector3d(0.0D, 1.0D, 0.0D),
                        Float.NaN, 4.0F, 0.05F, 100.0F));
    }

    @Test
    void orthographicProjectionKeepsScaleIndependentFromCameraDistance() {
        EditorViewport viewport = new EditorViewport(0, 0, 400, 400);
        EditorCameraState near = EditorCameraState.lookingAt(EditorCameraMode.ORTHOGRAPHIC,
                new Vector3d(0.0D, 0.0D, 5.0D), new Vector3d(), new Vector3d(0.0D, 1.0D, 0.0D),
                45.0F, 2.0F, 0.05F, 100.0F);
        EditorCameraState far = EditorCameraState.lookingAt(EditorCameraMode.ORTHOGRAPHIC,
                new Vector3d(0.0D, 0.0D, 20.0D), new Vector3d(), new Vector3d(0.0D, 1.0D, 0.0D),
                45.0F, 2.0F, 0.05F, 100.0F);

        ProjectedPoint nearPoint = EditorProjection.project(new Vector3d(1.0D, 0.0D, 0.0D),
                CameraMatrices.create(near, viewport), viewport);
        ProjectedPoint farPoint = EditorProjection.project(new Vector3d(1.0D, 0.0D, 0.0D),
                CameraMatrices.create(far, viewport), viewport);
        assertEquals(nearPoint.screenX(), farPoint.screenX(), EPSILON);
    }

    @Test
    void rayIntersectsOrientedRectangleAndRejectsMisses() {
        PickingRay ray = new PickingRay(new Vector3d(0.0D, 0.0D, 5.0D),
                new Vector3d(0.0D, 0.0D, -1.0D));
        var hit = ray.intersectRectangle(new Vector3d(), new Vector3d(1.0D, 0.0D, 0.0D),
                new Vector3d(0.0D, 1.0D, 0.0D), 2.0D, 1.0D).orElseThrow();
        assertEquals(5.0D, hit.distance(), EPSILON);
        assertEquals(0.0D, hit.localX(), EPSILON);
        assertEquals(0.0D, hit.localY(), EPSILON);

        PickingRay miss = new PickingRay(new Vector3d(3.0D, 0.0D, 5.0D),
                new Vector3d(0.0D, 0.0D, -1.0D));
        assertTrue(miss.intersectRectangle(new Vector3d(), new Vector3d(1.0D, 0.0D, 0.0D),
                new Vector3d(0.0D, 1.0D, 0.0D), 2.0D, 1.0D).isEmpty());

        PickingRay parallel = new PickingRay(new Vector3d(0.0D, 0.0D, 1.0D),
                new Vector3d(1.0D, 0.0D, 0.0D));
        assertTrue(parallel.intersectRectangle(new Vector3d(), new Vector3d(1.0D, 0.0D, 0.0D),
                new Vector3d(0.0D, 1.0D, 0.0D), 2.0D, 1.0D).isEmpty());
    }

    @Test
    void rayIntersectsAabbAcrossParallelInsideAndInvalidCases() {
        Vector3d minimum = new Vector3d(-1.0D, -1.0D, -1.0D);
        Vector3d maximum = new Vector3d(1.0D, 1.0D, 1.0D);
        PickingRay front = new PickingRay(new Vector3d(0.0D, 0.0D, 5.0D),
                new Vector3d(0.0D, 0.0D, -1.0D));
        assertEquals(4.0D, front.intersectAabb(minimum, maximum).orElseThrow(), EPSILON);

        PickingRay inside = new PickingRay(new Vector3d(), new Vector3d(1.0D, 0.0D, 0.0D));
        assertEquals(0.0D, inside.intersectAabb(minimum, maximum).orElseThrow(), EPSILON);

        PickingRay parallelMiss = new PickingRay(new Vector3d(2.0D, 0.0D, 5.0D),
                new Vector3d(0.0D, 0.0D, -1.0D));
        assertTrue(parallelMiss.intersectAabb(minimum, maximum).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> front.intersectAabb(maximum, minimum));
    }

    @Test
    void playerAabbPickingFollowsPannedCameraInsteadOfViewportCenter() {
        EditorViewport viewport = new EditorViewport(40, 60, 800, 500);
        Vector3d playerCenter = new Vector3d(0.0D, 0.925D, 0.0D);
        Vector3d minimum = new Vector3d(-0.345D, -0.03D, -0.345D);
        Vector3d maximum = new Vector3d(0.345D, 1.88D, 0.345D);
        EditorCameraState camera = EditorCameraState.lookingAt(EditorCameraMode.ORBIT,
                new Vector3d(0.0D, 1.4D, 6.0D), playerCenter, new Vector3d(0.0D, 1.0D, 0.0D),
                45.0F, 4.0F, 0.05F, 100.0F);
        EditorCameraController controller = new EditorCameraController();
        EditorCameraState panned = controller.panPixels(camera, 220.0D, 0.0D, viewport);
        CameraMatrices matrices = CameraMatrices.create(panned, viewport);
        ProjectedPoint projectedPlayer = EditorProjection.project(playerCenter, matrices, viewport);

        assertTrue(projectedPlayer.visible());
        assertTrue(Math.abs(projectedPlayer.screenX() - (viewport.x() + viewport.width() * 0.5D)) > 100.0D);
        assertTrue(EditorProjection.rayFromScreen(projectedPlayer.screenX(), projectedPlayer.screenY(), matrices,
                viewport).intersectAabb(minimum, maximum).isPresent());
        assertTrue(EditorProjection.rayFromScreen(viewport.x() + viewport.width() * 0.5D,
                viewport.y() + viewport.height() * 0.5D, matrices, viewport)
                .intersectAabb(minimum, maximum).isEmpty());
    }

        @Test
        void firstPersonCompatibilityFrameKeepsPositiveZScreenAtViewportCenter() {
                EditorViewport viewport = new EditorViewport(20, 30, 800, 500);
                Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70.0D),
                                (float) viewport.aspectRatio(), 0.05F, 100.0F);
                Matrix4f view = new Matrix4f().translate(0.0F, 0.0F, -0.001F)
                                .scale(1.0F, -1.0F, -1.0F).translate(0.0F, -1.62F, 0.0F);
                CameraMatrices matrices = CameraMatrices.from(view, projection);

                ProjectedPoint center = EditorProjection.project(new Vector3d(0.0D, 1.62D, 2.0D), matrices, viewport);
                assertTrue(center.visible());
                assertEquals(viewport.x() + viewport.width() * 0.5D, center.screenX(), EPSILON);
                assertEquals(viewport.y() + viewport.height() * 0.5D, center.screenY(), EPSILON);
        }
}