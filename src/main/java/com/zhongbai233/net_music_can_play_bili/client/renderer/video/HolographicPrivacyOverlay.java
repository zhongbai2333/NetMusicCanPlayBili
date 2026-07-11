package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.renderer.RenderVertexUtils;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/** 全息眼镜隐私保护提示层：替代视频画面，避免直播露出敏感内容。 */
public final class HolographicPrivacyOverlay {
    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/holographic_privacy_overlay.png");

    private HolographicPrivacyOverlay() {
    }

    public static Identifier textureId() {
        return TEXTURE_ID;
    }

    public static void release() {
    }

    public static void submit(SubmitNodeCollector collector, PoseStack poseStack,
            float p0x, float p0y, float p0z,
            float p1x, float p1y, float p1z,
            float p2x, float p2y, float p2z,
            float p3x, float p3y, float p3z) {
        collector.submitCustomGeometry(
                poseStack,
                RenderTypes.itemCutout(textureId()),
                (pose, buffer) -> {
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            false);
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            true);
                });
    }

    private static void emitQuad(VertexConsumer buffer, PoseStack.Pose pose,
            float p0x, float p0y, float p0z,
            float p1x, float p1y, float p1z,
            float p2x, float p2y, float p2z,
            float p3x, float p3y, float p3z,
            boolean reverse) {
        if (reverse) {
            vertex(buffer, pose, p3x, p3y, p3z, 1.0F, 0.0F);
            vertex(buffer, pose, p2x, p2y, p2z, 1.0F, 1.0F);
            vertex(buffer, pose, p1x, p1y, p1z, 0.0F, 1.0F);
            vertex(buffer, pose, p0x, p0y, p0z, 0.0F, 0.0F);
        } else {
            vertex(buffer, pose, p0x, p0y, p0z, 0.0F, 0.0F);
            vertex(buffer, pose, p1x, p1y, p1z, 0.0F, 1.0F);
            vertex(buffer, pose, p2x, p2y, p2z, 1.0F, 1.0F);
            vertex(buffer, pose, p3x, p3y, p3z, 1.0F, 0.0F);
        }
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float u,
            float v) {
        RenderVertexUtils.texturedVertex(buffer, pose, x, y, z, u, v);
    }
}
