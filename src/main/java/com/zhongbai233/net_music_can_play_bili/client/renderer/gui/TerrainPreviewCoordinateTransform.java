package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** 抵消原版 terrain shader 的主世界相机相对坐标变换。 */
final class TerrainPreviewCoordinateTransform {
    private TerrainPreviewCoordinateTransform() {
    }

    static Frame create(Matrix4fc editorModelView, double cameraX, double cameraY, double cameraZ) {
        int cameraBlockX = floorToInt(cameraX);
        int cameraBlockY = floorToInt(cameraY);
        int cameraBlockZ = floorToInt(cameraZ);
        float cameraFractionX = (float) (cameraX - cameraBlockX);
        float cameraFractionY = (float) (cameraY - cameraBlockY);
        float cameraFractionZ = (float) (cameraZ - cameraBlockZ);
        Matrix4f compensatedModelView = new Matrix4f(editorModelView)
                .translate(cameraFractionX, cameraFractionY, cameraFractionZ);
        return new Frame(compensatedModelView, cameraBlockX, cameraBlockY, cameraBlockZ);
    }

    private static int floorToInt(double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("camera coordinate must be a finite int-range value");
        }
        return (int) Math.floor(value);
    }

    record Frame(Matrix4f modelView, int cameraBlockX, int cameraBlockY, int cameraBlockZ) {
        Frame {
            modelView = new Matrix4f(java.util.Objects.requireNonNull(modelView, "modelView"));
        }

        int encodedSectionX(int localSectionX) {
            return Math.addExact(cameraBlockX, localSectionX);
        }

        int encodedSectionY(int localSectionY) {
            return Math.addExact(cameraBlockY, localSectionY);
        }

        int encodedSectionZ(int localSectionZ) {
            return Math.addExact(cameraBlockZ, localSectionZ);
        }
    }
}