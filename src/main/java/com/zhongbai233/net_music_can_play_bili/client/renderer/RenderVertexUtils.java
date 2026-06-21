package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Common helpers for small custom quads emitted through Minecraft's vertex API.
 */
public final class RenderVertexUtils {
    public static final int FULL_BRIGHT = 0x00F000F0;

    private RenderVertexUtils() {
    }

    public static void texturedVertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z,
            float u, float v) {
        buffer.addVertex(pose, x, y, z)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }
}