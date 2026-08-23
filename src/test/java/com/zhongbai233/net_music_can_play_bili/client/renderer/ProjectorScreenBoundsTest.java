package com.zhongbai233.net_music_can_play_bili.client.renderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectorScreenBoundsTest {
    @Test
    void coversMovedAndOversizedProjectorScreens() {
        double centerX = 75.5D;
        double centerY = 13.0D;
        double centerZ = 16.5D;
        double halfHeight = ProjectorScreenGeometry.BASE_HEIGHT * 3.0D * 0.5D;
        double halfWidth = halfHeight * 8.0D;

        ProjectorScreenGeometry.Bounds wide = ProjectorScreenGeometry
                .aroundCenter(centerX, centerY, centerZ, 0.0D, 0.0D, 3.0D, 8.0D, 0.0D)
                .include(70.0D, 10.0D, 20.0D, 71.0D, 11.0D, 21.0D);
        assertContains(wide, centerX - halfWidth, centerY - halfHeight, centerZ);
        assertContains(wide, centerX + halfWidth, centerY + halfHeight, centerZ);

        ProjectorScreenGeometry.Bounds rotated = ProjectorScreenGeometry.aroundCenter(
                centerX, centerY, centerZ, 90.0D, 45.0D, 3.0D, 8.0D, 0.0D);
        double diagonalUp = halfHeight / Math.sqrt(2.0D);
        assertContains(rotated, centerX - diagonalUp, centerY + diagonalUp, centerZ - halfWidth);
        assertContains(rotated, centerX + diagonalUp, centerY - diagonalUp, centerZ + halfWidth);
    }

    @Test
    void measuresDistanceFromScreenEdgeInsteadOfCenter() {
        ProjectorScreenGeometry.Bounds edgeInsideRange = ProjectorScreenGeometry.aroundCenter(
                70.0D, 0.0D, 0.0D, 0.0D, 0.0D, 3.0D, 8.0D, 0.0D);

        assertTrue(ProjectorScreenGeometry.distanceToSqr(edgeInsideRange, 0.0D, 0.0D, 0.0D)
                < 64.0D * 64.0D);
        assertEquals(0.0D, ProjectorScreenGeometry.distanceToSqr(edgeInsideRange, 70.0D, 0.0D, 0.0D));
    }

    @Test
    void displacedScreenBoundsDoNotSpanBackToTheProjectorBlock() {
        ProjectorScreenGeometry.Bounds bounds = ProjectorScreenGeometry.aroundCenter(
                50.5D, 2.0D, 0.5D, 0.0D, 0.0D, 1.0D, 16.0D / 9.0D, 0.0D);

        assertTrue(bounds.minX() > 40.0D,
                "屏幕 BER 边界不能把投影仪方块到远端屏幕之间的整片空间都算作可见");
        assertTrue(bounds.maxX() < 60.0D);
    }

    private static void assertContains(ProjectorScreenGeometry.Bounds bounds, double x, double y, double z) {
        double epsilon = 1.0e-8D;
        assertTrue(x >= bounds.minX() - epsilon && x <= bounds.maxX() + epsilon
                && y >= bounds.minY() - epsilon && y <= bounds.maxY() + epsilon
                && z >= bounds.minZ() - epsilon && z <= bounds.maxZ() + epsilon,
                () -> "projector bounds " + bounds + " do not contain " + x + "," + y + "," + z);
    }
}