package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频帧 → 彩色 █ 文字渲染。
 *
 * 不碰 OpenGL，纯走 MC 文字渲染管线 (SubmitNodeCollector.submitText)。
 * 天然兼容 Sodium/Iris。
 */
public final class VideoCharRenderer {

    private static final float TEXT_SCALE = 0.025F;
    private static final float CHAR_STEP = 10.0F;  // 字符间距

    private VideoCharRenderer() {
    }

    /**
     * 在世界上渲染一帧视频。
     *
     * @param rgba     RGBA packed byte[] (width*height*4)
     * @param cols     每行像素数
     * @param rows     行数
     * @param worldPos 世界坐标（屏幕中心位置）
     */
    public static void renderFrame(byte[] rgba, int cols, int rows,
                                   Vec3 worldPos, PoseStack poseStack,
                                   SubmitNodeCollector collector,
                                   CameraRenderState cameraState, Font font) {
        if (rgba == null || rgba.length < cols * rows * 4)
            return;

        poseStack.pushPose();
        // 定位到世界坐标
        poseStack.translate(worldPos.x, worldPos.y, worldPos.z);
        // 面朝相机
        poseStack.mulPose(Axis.YN.rotationDegrees(cameraState.yRot));
        poseStack.mulPose(Axis.XN.rotationDegrees(-cameraState.xRot));
        poseStack.scale(-TEXT_SCALE, -TEXT_SCALE, -TEXT_SCALE);

        float totalW = cols * CHAR_STEP;
        float totalH = rows * CHAR_STEP;
        float startX = -totalW / 2.0F + CHAR_STEP / 2.0F;
        float startY = totalH / 2.0F - CHAR_STEP / 2.0F;

        for (int row = 0; row < rows; row++) {
            int rowStart = row * cols * 4;
            float y = startY - row * CHAR_STEP;

            // Run-length 编码：合并同行同色像素
            List<Segment> segments = new ArrayList<>();
            int segStart = 0;
            int prevColor = 0;
            boolean hasPrev = false;

            for (int col = 0; col < cols; col++) {
                int i = rowStart + col * 4;
                int r = rgba[i] & 0xFF;
                int g = rgba[i + 1] & 0xFF;
                int b = rgba[i + 2] & 0xFF;
                int color = (r << 16) | (g << 8) | b;

                if (!hasPrev) {
                    prevColor = color;
                    hasPrev = true;
                    continue;
                }

                if (color != prevColor) {
                    segments.add(new Segment(segStart, col, prevColor));
                    segStart = col;
                    prevColor = color;
                }
            }
            // 最后一段
            if (hasPrev) {
                segments.add(new Segment(segStart, cols, prevColor));
            }

            // 渲染每段
            for (Segment seg : segments) {
                int segLen = seg.endCol - seg.startCol;
                if (segLen <= 0)
                    continue;

                StringBuilder sb = new StringBuilder(segLen);
                for (int k = 0; k < segLen; k++) {
                    sb.append('\u2588'); // █
                }

                float x = startX + seg.startCol * CHAR_STEP;
                Component text = Component.literal(sb.toString());
                FormattedCharSequence visual = text.getVisualOrderText();

                int bgColor = (seg.color & 0xFFFFFF) | 0xFF000000; // 全不透明

                collector.submitText(poseStack, x, y, visual, false,
                        Font.DisplayMode.NORMAL,
                        0x00F000F0,  // fullbright
                        0xFFFFFFFF,  // white text (we use background color)
                        bgColor, 0);
            }
        }

        poseStack.popPose();
    }

    private record Segment(int startCol, int endCol, int color) {
    }
}
