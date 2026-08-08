package com.zhongbai233.net_music_can_play_bili.terrain.core;

/** 将原版流体渲染器产生的 section 局部顶点与预览方块模型的半格原点对齐。 */
public final class TerrainFluidVertexCoordinates {
    private TerrainFluidVertexCoordinates() {
    }

    public static float offsetX() {
        return -0.5F;
    }

    public static float offsetY() {
        return 0.0F;
    }

    public static float offsetZ() {
        return -0.5F;
    }

    public static float previewX(float sectionLocalX) {
        return sectionLocalX + offsetX();
    }

    public static float previewY(float sectionLocalY) {
        return sectionLocalY + offsetY();
    }

    public static float previewZ(float sectionLocalZ) {
        return sectionLocalZ + offsetZ();
    }
}