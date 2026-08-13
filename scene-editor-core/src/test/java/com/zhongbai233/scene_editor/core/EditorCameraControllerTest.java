package com.zhongbai233.scene_editor.core;

import com.zhongbai233.scene_editor.core.camera.EditorCameraController;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.camera.StandardCameraView;
import com.zhongbai233.scene_editor.core.camera.CameraMatrices;
import com.zhongbai233.scene_editor.core.projection.EditorProjection;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorCameraControllerTest {
    private static final double EPSILON = 1.0e-5D;
    private static final Vector3d UP = new Vector3d(0.0D, 1.0D, 0.0D);
    private final EditorCameraController controller = new EditorCameraController();

    @Test
    void orbitKeepsFocusAndDistance() {
        EditorCameraState initial = camera(EditorCameraMode.ORBIT, new Vector3d(0.0D, 0.0D, 10.0D));
        EditorCameraState result = controller.orbit(initial, Math.PI * 0.5D, 0.0D, UP);

        assertVector(result.focus(), 0.0D, 0.0D, 0.0D);
        assertEquals(10.0D, result.position().distance(result.focus()), EPSILON);
        assertVector(result.position(), -10.0D, 0.0D, 0.0D);
        Vector3f forward = result.orientation().transform(new Vector3f(0.0F, 0.0F, -1.0F));
        assertEquals(1.0D, forward.x, EPSILON);
    }

    @Test
    void panMovesPositionAndFocusTogether() {
        EditorCameraState initial = camera(EditorCameraMode.ORBIT, new Vector3d(0.0D, 0.0D, 10.0D));
        EditorCameraState result = controller.panPixels(initial, 100.0D, 50.0D,
                new EditorViewport(0, 0, 800, 400));

        Vector3d positionDelta = result.position().sub(initial.position());
        Vector3d focusDelta = result.focus().sub(initial.focus());
        assertVector(positionDelta, focusDelta.x, focusDelta.y, focusDelta.z);
        assertTrue(positionDelta.x < 0.0D);
        assertTrue(positionDelta.y > 0.0D);
    }

    @Test
    void dollyMovesPerspectiveCameraAndScalesOrthographicCamera() {
        EditorCameraState perspective = camera(EditorCameraMode.ORBIT, new Vector3d(0.0D, 0.0D, 10.0D));
        EditorCameraState closer = controller.dolly(perspective, 1.0D);
        assertTrue(closer.position().distance(closer.focus()) < 10.0D);
        assertVector(closer.focus(), 0.0D, 0.0D, 0.0D);

        EditorCameraState orthographic = camera(EditorCameraMode.ORTHOGRAPHIC,
                new Vector3d(0.0D, 0.0D, 10.0D));
        EditorCameraState zoomed = controller.dolly(orthographic, 1.0D);
        assertTrue(zoomed.orthoScale() < orthographic.orthoScale());
        assertVector(zoomed.position(), 0.0D, 0.0D, 10.0D);
    }

    @Test
    void flyUsesCameraForwardAndKeepsFocusDistance() {
        EditorCameraState initial = camera(EditorCameraMode.FLY, new Vector3d(0.0D, 0.0D, 10.0D));
        EditorCameraState result = controller.fly(initial, new Vector3d(0.0D, 0.0D, 1.0D),
                0.5D, 1.0D, UP);
        assertVector(result.position(), 0.0D, 0.0D, 6.0D);
        assertEquals(10.0D, result.position().distance(result.focus()), EPSILON);
    }

    @Test
    void keyboardFlyMapsWasdAndQeAndSupportsBoost() {
        EditorCameraState initial = camera(EditorCameraMode.FLY, new Vector3d(0.0D, 0.0D, 10.0D));
        EditorCameraState result = controller.fly(initial, true, false, false, true, false, true,
                0.5D, false, UP);

        double expected = 8.0D * 0.5D / Math.sqrt(3.0D);
        assertVector(result.position(), expected, expected, 10.0D - expected);
        assertVector(result.focus(), expected, expected, -expected);

        EditorCameraState boosted = controller.fly(initial, true, false, false, false, false, false,
                0.5D, true, UP);
        assertEquals(10.0D - 8.0D * 0.5D * 4.0D, boosted.position().z, EPSILON);
    }

    @Test
    void keyboardFlyDistanceIsIndependentFromRenderFrameRate() {
        EditorCameraState twentyHz = flyForOneSecond(20);
        EditorCameraState sixtyHz = flyForOneSecond(60);
        EditorCameraState oneFortyFourHz = flyForOneSecond(144);

        assertEquals(twentyHz.position().z, sixtyHz.position().z, EPSILON);
        assertEquals(twentyHz.position().z, oneFortyFourHz.position().z, EPSILON);
        assertEquals(2.0D, twentyHz.position().z, EPSILON);
    }

        @Test
        void thirdPersonWalkKeepsMovementHorizontalWhenCameraLooksDown() {
        EditorCameraState pitched = EditorCameraState.lookingAt(EditorCameraMode.ORBIT,
            new Vector3d(0.0D, 8.0D, 10.0D), new Vector3d(0.0D, 0.0D, 0.0D), UP,
            45.0F, 4.0F, 0.05F, 100.0F);
        EditorCameraState moved = controller.walk(pitched, true, false, false, false,
            false, false, 0.5D, false, UP);

        assertEquals(pitched.position().y, moved.position().y, EPSILON);
        assertEquals(pitched.focus().y, moved.focus().y, EPSILON);
        assertEquals(4.0D, moved.position().sub(pitched.position()).length(), EPSILON);
        assertEquals(pitched.position().distance(pitched.focus()),
            moved.position().distance(moved.focus()), EPSILON);
        }

        @Test
        void thirdPersonWalkSupportsSeparateVerticalMovement() {
        EditorCameraState initial = camera(EditorCameraMode.ORBIT, new Vector3d(0.0D, 0.0D, 10.0D));
        EditorCameraState moved = controller.walk(initial, false, false, false, false,
            false, true, 0.25D, false, UP);
        assertVector(moved.position(), 0.0D, 2.0D, 10.0D);
        assertVector(moved.focus(), 0.0D, 2.0D, 0.0D);
        }

    @Test
    void focusFramesBoundsAndTopViewAvoidsDegenerateUp() {
        EditorCameraState initial = camera(EditorCameraMode.ORBIT, new Vector3d(0.0D, 0.0D, 10.0D));
        EditorCameraState focused = controller.focus(initial, new Vector3d(3.0D, 4.0D, 5.0D), 2.0D,
                new EditorViewport(0, 0, 800, 400), UP);
        assertVector(focused.focus(), 3.0D, 4.0D, 5.0D);
        assertTrue(focused.position().distance(focused.focus()) > 2.0D);

        EditorCameraState top = controller.standardView(focused, StandardCameraView.TOP, UP);
        assertTrue(top.position().y > top.focus().y);
        Vector3f forward = top.orientation().transform(new Vector3f(0.0F, 0.0F, -1.0F));
        assertEquals(-1.0D, forward.y, EPSILON);
    }

        @Test
        void projectionSwitchKeepsFocusPlaneFraming() {
        EditorViewport viewport = new EditorViewport(0, 0, 800, 400);
        EditorCameraState perspective = camera(EditorCameraMode.ORBIT, new Vector3d(0.0D, 0.0D, 10.0D));
        Vector3d point = new Vector3d(1.0D, 0.0D, 0.0D);
        double perspectiveX = EditorProjection.project(point, CameraMatrices.create(perspective, viewport), viewport)
            .screenX();

        EditorCameraState orthographic = controller.switchProjection(perspective, EditorCameraMode.ORTHOGRAPHIC);
        double orthographicX = EditorProjection.project(point, CameraMatrices.create(orthographic, viewport), viewport)
            .screenX();
        assertEquals(perspectiveX, orthographicX, EPSILON);

        EditorCameraState roundTrip = controller.switchProjection(orthographic, EditorCameraMode.ORBIT);
        assertEquals(10.0D, roundTrip.position().distance(roundTrip.focus()), EPSILON);
        assertThrows(IllegalArgumentException.class,
            () -> controller.switchProjection(perspective, EditorCameraMode.FLY));
        }

    private static EditorCameraState camera(EditorCameraMode mode, Vector3d position) {
        return EditorCameraState.lookingAt(mode, position, new Vector3d(), UP,
                45.0F, 4.0F, 0.05F, 100.0F);
    }

    private EditorCameraState flyForOneSecond(int frames) {
        EditorCameraState state = camera(EditorCameraMode.FLY, new Vector3d(0.0D, 0.0D, 10.0D));
        for (int i = 0; i < frames; i++) {
            state = controller.fly(state, true, false, false, false, false, false,
                    1.0D / frames, false, UP);
        }
        return state;
    }

    private static void assertVector(Vector3d actual, double x, double y, double z) {
        assertEquals(x, actual.x, EPSILON);
        assertEquals(y, actual.y, EPSILON);
        assertEquals(z, actual.z, EPSILON);
    }
}