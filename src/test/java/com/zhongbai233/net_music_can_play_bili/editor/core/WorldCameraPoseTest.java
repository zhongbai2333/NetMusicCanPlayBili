package com.zhongbai233.net_music_can_play_bili.editor.core;

import com.zhongbai233.net_music_can_play_bili.editor.core.camera.WorldCameraPose;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldCameraPoseTest {
    private static final double EPSILON = 1.0e-5D;

    @Test
    void localPositionUsesConsoleBottomCenterAsWorldOrigin() {
        WorldCameraPose pose = WorldCameraPose.fromLocal(new Vector3d(10.5D, 64.0D, -19.5D),
                new Vector3d(1.0D, 2.0D, 3.0D), new Quaternionf(), 37.0F);
        assertVector(pose.position(), 11.5D, 66.0D, -16.5D);
    }

    @Test
    void identityEditorCameraLooksTowardMinecraftNegativeZ() {
        WorldCameraPose pose = WorldCameraPose.fromLocal(new Vector3d(), new Vector3d(),
                new Quaternionf(), 0.0F);
        assertAngle(180.0F, pose.yawDegrees());
        assertEquals(0.0F, pose.pitchDegrees(), EPSILON);
    }

    @Test
    void minecraftCardinalDirectionsRoundTrip() {
        assertDirection(new Quaternionf().rotateY((float) Math.toRadians(180.0D)), 0.0D, 0.0D, 1.0D);
        assertDirection(new Quaternionf().rotateY((float) Math.toRadians(90.0D)), -1.0D, 0.0D, 0.0D);
        assertDirection(new Quaternionf(), 0.0D, 0.0D, -1.0D);
        assertDirection(new Quaternionf().rotateY((float) Math.toRadians(-90.0D)), 1.0D, 0.0D, 0.0D);
    }

    @Test
    void verticalViewKeepsFallbackYaw() {
        WorldCameraPose up = WorldCameraPose.fromLocal(new Vector3d(), new Vector3d(),
                new Quaternionf().rotateX((float) Math.toRadians(90.0D)), 42.0F);
        assertEquals(42.0F, up.yawDegrees(), EPSILON);
        assertEquals(-90.0F, up.pitchDegrees(), EPSILON);
    }

    @Test
    void rejectsInvalidOrientation() {
        assertThrows(IllegalArgumentException.class, () -> WorldCameraPose.fromLocal(
                new Vector3d(), new Vector3d(), new Quaternionf(0.0F, 0.0F, 0.0F, 0.0F), 0.0F));
    }

    private static void assertDirection(Quaternionf orientation, double expectedX, double expectedY,
            double expectedZ) {
        WorldCameraPose pose = WorldCameraPose.fromLocal(new Vector3d(), new Vector3d(), orientation, 0.0F);
        double yaw = Math.toRadians(pose.yawDegrees());
        double pitch = Math.toRadians(pose.pitchDegrees());
        double cosPitch = Math.cos(pitch);
        assertEquals(expectedX, -Math.sin(yaw) * cosPitch, EPSILON);
        assertEquals(expectedY, -Math.sin(pitch), EPSILON);
        assertEquals(expectedZ, Math.cos(yaw) * cosPitch, EPSILON);
    }

    private static void assertAngle(float expected, float actual) {
        double delta = Math.IEEEremainder(actual - expected, 360.0D);
        assertEquals(0.0D, delta, EPSILON);
    }

    private static void assertVector(Vector3d actual, double x, double y, double z) {
        assertEquals(x, actual.x, EPSILON);
        assertEquals(y, actual.y, EPSILON);
        assertEquals(z, actual.z, EPSILON);
    }
}