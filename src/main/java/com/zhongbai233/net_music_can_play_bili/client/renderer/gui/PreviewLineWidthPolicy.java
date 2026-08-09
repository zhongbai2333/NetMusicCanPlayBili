package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

/** 中控台建模预览的屏幕可见线宽策略。 */
final class PreviewLineWidthPolicy {
    private static final float BASE_SCALE = 1.30F;
    private static final float REFERENCE_DISTANCE = 4.0F;
    private static final float REFERENCE_ORTHO_HALF_HEIGHT = 3.0F;
    private static final float MAX_SCALE = 4.0F;

    private PreviewLineWidthPolicy() {
    }

    static float perspective(float cameraDistance) {
        float distanceRatio = Math.max(1.0F, finitePositive(cameraDistance) / REFERENCE_DISTANCE);
        return clamp(BASE_SCALE * (float) Math.sqrt(distanceRatio));
    }

    static float orthographic(float halfHeight) {
        float viewRatio = Math.max(1.0F, finitePositive(halfHeight) / REFERENCE_ORTHO_HALF_HEIGHT);
        return clamp(BASE_SCALE * (float) Math.sqrt(viewRatio));
    }

    static float firstPerson() {
        return BASE_SCALE;
    }

    private static float finitePositive(float value) {
        return Float.isFinite(value) && value > 0.0F ? value : 1.0F;
    }

    private static float clamp(float value) {
        return Math.clamp(value, BASE_SCALE, MAX_SCALE);
    }
}