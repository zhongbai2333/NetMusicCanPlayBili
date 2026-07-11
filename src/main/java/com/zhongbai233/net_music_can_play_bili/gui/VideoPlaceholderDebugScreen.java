package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Debug screen for baked video placeholder textures. */
public final class VideoPlaceholderDebugScreen extends Screen {
    private static final int TEX_W = 320;
    private static final int TEX_H = 180;
    private static final Identifier LOADING = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/video_loading/loading_base_phase2.png");
    private static final Identifier IRIS_WARNING = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/video_loading/iris_translucent_warning_base.png");
    private static final Identifier PRIVACY = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/holographic_privacy_overlay.png");
    private static final Identifier PROGRESS_FRAME = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/video_loading/progress_frame_204x10.png");
    private static final Identifier PROGRESS_SEGMENT = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/video_loading/progress_segment_42x6.png");

    public VideoPlaceholderDebugScreen() {
        super(Component.literal("Video Placeholder Debug"));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF05070B);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int scale = width >= 1040 ? 1 : Math.max(1, Math.min(width / 360, height / 240));
        int drawW = TEX_W * scale;
        int drawH = TEX_H * scale;
        int gap = 18;
        int totalW = drawW * 3 + gap * 2;
        int startX = Math.max(8, (width - totalW) / 2);
        int y = Math.max(28, (height - drawH) / 2);

        drawTexture(graphics, LOADING, startX, y, drawW, drawH, "LOADING + PROGRESS");
        drawProgress(graphics, startX, y, scale);
        drawTexture(graphics, IRIS_WARNING, startX + drawW + gap, y, drawW, drawH, "IRIS WARNING");
        drawTexture(graphics, PRIVACY, startX + (drawW + gap) * 2, y, drawW, drawH, "HOLO PRIVACY");

        graphics.centeredText(font, Component.literal("Video placeholder textures - press Esc to close"), width / 2,
                10, 0xFFE8E8E8);
    }

    private void drawTexture(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int w, int h,
            String label) {
        graphics.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF1B2430);
        graphics.blit(texture, x, y, x + w, y + h, 0.0F, 1.0F, 0.0F, 1.0F);
        graphics.centeredText(font, Component.literal(label), x + w / 2, y + h + 6, 0xFFBDE7FF);
    }

    private void drawProgress(GuiGraphicsExtractor graphics, int x, int y, int scale) {
        int progressX = x + 58 * scale;
        int progressY = y + 126 * scale;
        graphics.blit(PROGRESS_FRAME, progressX, progressY, progressX + 204 * scale, progressY + 10 * scale,
                0.0F, 1.0F, 0.0F, 1.0F);
        int movingX = progressX + 2 * scale + (int) (((System.nanoTime() / 12_000_000L) % (204 - 42 - 4)) * scale);
        int movingY = progressY + 2 * scale;
        graphics.blit(PROGRESS_SEGMENT, movingX, movingY, movingX + 42 * scale, movingY + 6 * scale,
                0.0F, 1.0F, 0.0F, 1.0F);
    }
}
