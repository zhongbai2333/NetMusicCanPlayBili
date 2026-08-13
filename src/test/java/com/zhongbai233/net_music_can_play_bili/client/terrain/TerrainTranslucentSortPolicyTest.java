package com.zhongbai233.net_music_can_play_bili.client.terrain;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainTranslucentSortPolicyTest {
    @Test
    void onlyCameraMatrixChangesInvalidateTheQuadOrder() {
        Matrix4f original = new Matrix4f().translation(1.0F, 2.0F, 3.0F);
        assertTrue(TerrainTranslucentSortPolicy.needsResort(null, original));
        assertFalse(TerrainTranslucentSortPolicy.needsResort(new Matrix4f(original), original));
        assertTrue(TerrainTranslucentSortPolicy.needsResort(
                new Matrix4f(original), new Matrix4f(original).rotateY(0.1F)));
    }

    @Test
    void distanceUsesSectionOffsetAndFullModelViewTransform() {
        Matrix4f modelView = new Matrix4f().translation(-10.0F, 0.0F, 0.0F);
        float near = TerrainTranslucentSortPolicy.viewDistanceSquared(
                modelView, 8.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F);
        float far = TerrainTranslucentSortPolicy.viewDistanceSquared(
                modelView, 24.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F);
        assertTrue(far > near);
    }
}
