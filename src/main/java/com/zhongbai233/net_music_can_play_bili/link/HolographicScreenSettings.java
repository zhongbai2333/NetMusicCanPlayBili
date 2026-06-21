package com.zhongbai233.net_music_can_play_bili.link;

/** 全息屏幕摆放参数的共享默认值与限制范围。 */
public final class HolographicScreenSettings {
    public static final float DEFAULT_DISTANCE = 2.2F;
    public static final float DEFAULT_OFFSET_X = 0.0F;
    public static final float DEFAULT_OFFSET_Y = 0.05F;
    public static final float DEFAULT_HEIGHT = 0.75F;
    public static final float DEFAULT_ASPECT = 16.0F / 9.0F;
    public static final float DEFAULT_ROLL = 0.0F;

    public static final float MIN_DISTANCE = 0.8F;
    public static final float MAX_DISTANCE = 5.0F;
    public static final float MIN_OFFSET_X = -2.0F;
    public static final float MAX_OFFSET_X = 2.0F;
    public static final float MIN_OFFSET_Y = -1.2F;
    public static final float MAX_OFFSET_Y = 1.6F;
    public static final float MIN_HEIGHT = 0.25F;
    public static final float MAX_HEIGHT = 2.2F;
    public static final float MIN_ASPECT = 1.0F;
    public static final float MAX_ASPECT = 2.4F;
    public static final float MIN_ROLL = -90.0F;
    public static final float MAX_ROLL = 90.0F;

    public static final float DEFAULT_PREVIEW_SCALE = 38.0F;
    public static final float ORBIT_FOV_DEGREES = 45.0F;
    public static final float ORBIT_DEFAULT_CAMERA_DISTANCE = 6.5F;
    public static final float ORBIT_TARGET_Y = 0.9F;
    public static final float GIZMO_AXIS_WORLD_LEN = 0.72F;

    private HolographicScreenSettings() {
    }

    public static float clampDistance(float value) {
        return clamp(value, MIN_DISTANCE, MAX_DISTANCE);
    }

    public static float clampOffsetX(float value) {
        return clamp(value, MIN_OFFSET_X, MAX_OFFSET_X);
    }

    public static float clampOffsetY(float value) {
        return clamp(value, MIN_OFFSET_Y, MAX_OFFSET_Y);
    }

    public static float clampHeight(float value) {
        return clamp(value, MIN_HEIGHT, MAX_HEIGHT);
    }

    public static float clampAspect(float value) {
        return clamp(value, MIN_ASPECT, MAX_ASPECT);
    }

    public static float clampRoll(float value) {
        return clamp(value, MIN_ROLL, MAX_ROLL);
    }

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
