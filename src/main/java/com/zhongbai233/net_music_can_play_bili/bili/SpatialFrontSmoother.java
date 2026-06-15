package com.zhongbai233.net_music_can_play_bili.bili;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** 在不延迟重新居中的情况下解析空间音频正前方向。 */
final class SpatialFrontSmoother {
    private final float[] resolved = new float[] { 0.0F, 0.0F, 1.0F };

    float[] update(float[] fallbackForward, boolean followLocalPlayer) {
        float[] target = followLocalPlayer ? playerForward(fallbackForward) : normalizedForward(fallbackForward);
        copy(target, resolved);
        return resolved;
    }

    float[] resetTo(float[] fallbackForward) {
        float[] target = normalizedForward(fallbackForward);
        copy(target, resolved);
        return resolved;
    }

    private static float[] playerForward(float[] fallback) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft != null ? minecraft.player : null;
        if (player != null) {
            Vec3 look = player.getLookAngle();
            float x = (float) look.x;
            float z = (float) look.z;
            float len = (float) Math.sqrt(x * x + z * z);
            if (len > 0.001F) {
                return new float[] { x / len, 0.0F, z / len };
            }
        }
        return normalizedForward(fallback);
    }

    private static float[] normalizedForward(float[] fallback) {
        if (fallback == null || fallback.length < 3) {
            return new float[] { 0.0F, 0.0F, 1.0F };
        }
        float x = fallback[0];
        float z = fallback[2];
        float len = (float) Math.sqrt(x * x + z * z);
        if (len <= 0.001F) {
            return new float[] { 0.0F, 0.0F, 1.0F };
        }
        return new float[] { x / len, 0.0F, z / len };
    }

    private static void copy(float[] source, float[] target) {
        target[0] = source[0];
        target[1] = 0.0F;
        target[2] = source[2];
        normalize(target);
    }

    private static void normalize(float[] vector) {
        float len = (float) Math.sqrt(vector[0] * vector[0] + vector[2] * vector[2]);
        if (len <= 0.001F) {
            vector[0] = 0.0F;
            vector[1] = 0.0F;
            vector[2] = 1.0F;
            return;
        }
        vector[0] /= len;
        vector[1] = 0.0F;
        vector[2] /= len;
    }

}