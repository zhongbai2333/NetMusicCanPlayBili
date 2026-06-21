package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.renderer.RenderVertexUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/** 全息眼镜隐私保护提示层：替代视频画面，避免直播露出敏感内容。 */
public final class HolographicPrivacyOverlay {
    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "dynamic/holographic_privacy_overlay");
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;
    private static DynamicTexture texture;

    private HolographicPrivacyOverlay() {
    }

    public static Identifier textureId() {
        ensureTexture();
        return TEXTURE_ID;
    }

    public static void release() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
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

    private static void ensureTexture() {
        if (texture != null) {
            NativeImage image = texture.getPixels();
            if (image != null && !image.isClosed()) {
                return;
            }
        }
        texture = new DynamicTexture("holographic_privacy_overlay", WIDTH, HEIGHT, false);
        NativeImage image = texture.getPixels();
        if (image != null) {
            draw(image);
        }
        Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
        texture.upload();
    }

    private static void draw(NativeImage image) {
        fill(image, 0, 0, WIDTH, HEIGHT, 0xEE071017);
        fill(image, 8, 8, WIDTH - 16, HEIGHT - 16, 0xAA0D2B36);
        rect(image, 8, 8, WIDTH - 16, HEIGHT - 16, 0xFF55E6FF);
        rect(image, 18, 18, WIDTH - 36, HEIGHT - 36, 0xFF1B6F82);
        fill(image, 28, 72, WIDTH - 56, 36, 0xC9111A22);
        drawCenteredText(image, "STREAMER", 42, 0xFF8EF6FF);
        drawCenteredText(image, "SAFE MODE", 66, 0xFFFFFFFF);
        drawCenteredText(image, "VIDEO HIDDEN", 94, 0xFFBFEFFF);
        for (int x = 0; x < WIDTH; x += 18) {
            fill(image, x, 0, 9, 2, 0x8855E6FF);
            fill(image, WIDTH - x - 9, HEIGHT - 2, 9, 2, 0x8855E6FF);
        }
    }

    private static void rect(NativeImage image, int x, int y, int w, int h, int color) {
        fill(image, x, y, w, 1, color);
        fill(image, x, y + h - 1, w, 1, color);
        fill(image, x, y, 1, h, color);
        fill(image, x + w - 1, y, 1, h, color);
    }

    private static void fill(NativeImage image, int x, int y, int w, int h, int color) {
        int maxX = Math.min(WIDTH, x + Math.max(0, w));
        int maxY = Math.min(HEIGHT, y + Math.max(0, h));
        for (int py = Math.max(0, y); py < maxY; py++) {
            for (int px = Math.max(0, x); px < maxX; px++) {
                image.setPixel(px, py, color);
            }
        }
    }

    private static void drawCenteredText(NativeImage image, String text, int y, int color) {
        drawText(image, text, (WIDTH - textWidth(text)) / 2, y, color);
    }

    private static void drawText(NativeImage image, String text, int x, int y, int color) {
        drawTextRaw(image, text, x + 1, y + 1, 0xAA000000);
        drawTextRaw(image, text, x, y, color);
    }

    private static int textWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) == ' ' ? 4 : 12;
        }
        return Math.max(0, width - 2);
    }

    private static void drawTextRaw(NativeImage image, String text, int x, int y, int color) {
        int cursor = x;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toUpperCase(text.charAt(i));
            if (c == ' ') {
                cursor += 4;
                continue;
            }
            int[] glyph = glyph(c);
            for (int row = 0; row < 7; row++) {
                int bits = glyph[row];
                for (int col = 0; col < 5; col++) {
                    if ((bits & (1 << (4 - col))) != 0) {
                        fill(image, cursor + col * 2, y + row * 2, 2, 2, color);
                    }
                }
            }
            cursor += 12;
        }
    }

    private static int[] glyph(char c) {
        return switch (c) {
            case 'A' -> new int[] { 0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001 };
            case 'D' -> new int[] { 0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110 };
            case 'E' -> new int[] { 0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111 };
            case 'F' -> new int[] { 0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000 };
            case 'H' -> new int[] { 0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001 };
            case 'I' -> new int[] { 0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b11111 };
            case 'M' -> new int[] { 0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001 };
            case 'N' -> new int[] { 0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001 };
            case 'O' -> new int[] { 0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110 };
            case 'R' -> new int[] { 0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001 };
            case 'S' -> new int[] { 0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110 };
            case 'T' -> new int[] { 0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100 };
            case 'V' -> new int[] { 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100 };
            default -> new int[] { 0, 0, 0, 0, 0, 0, 0 };
        };
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
