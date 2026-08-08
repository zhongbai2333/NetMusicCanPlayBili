package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainPreviewCoordinateTransformTest {
    @Test
    void cancelsTerrainShadersMainCameraOffsetForLocalScene() {
        Matrix4f editorView = new Matrix4f()
                .rotateY(0.37F)
                .translate(-4.0F, 2.0F, -11.0F);
        double cameraX = 1109.921171550014D;
        double cameraY = 26.25D;
        double cameraZ = 1218.4818243917139D;
        int localSectionX = -32;
        int localSectionY = 16;
        int localSectionZ = 48;
        Vector4f localVertex = new Vector4f(7.5F, 3.0F, 12.5F, 1.0F);

        TerrainPreviewCoordinateTransform.Frame transform =
                TerrainPreviewCoordinateTransform.create(editorView, cameraX, cameraY, cameraZ);
        Vector4f expected = new Vector4f(localVertex)
                .add(localSectionX, localSectionY, localSectionZ, 0.0F)
                .mul(editorView);

        float cameraOffsetX = (float) (Math.floor(cameraX) - cameraX);
        float cameraOffsetY = (float) (Math.floor(cameraY) - cameraY);
        float cameraOffsetZ = (float) (Math.floor(cameraZ) - cameraZ);
        Vector4f shaderPosition = new Vector4f(localVertex)
                .add(transform.encodedSectionX(localSectionX) - (float) Math.floor(cameraX) + cameraOffsetX,
                        transform.encodedSectionY(localSectionY) - (float) Math.floor(cameraY) + cameraOffsetY,
                        transform.encodedSectionZ(localSectionZ) - (float) Math.floor(cameraZ) + cameraOffsetZ,
                        0.0F)
                .mul(transform.modelView());

        assertEquals(expected.x, shaderPosition.x, 1.0e-4F);
        assertEquals(expected.y, shaderPosition.y, 1.0e-4F);
        assertEquals(expected.z, shaderPosition.z, 1.0e-4F);
        assertEquals(expected.w, shaderPosition.w, 1.0e-4F);
    }
}