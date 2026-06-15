package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** MP4 图形界面纹理使用的像素字体合成器。 */
final class MP4GuiTextRenderer {
    private static final Font TEXT_FONT = MP4FontManager.loadBaseFontOrFallback().deriveFont(Font.PLAIN, 10.0F);
    private static final Font TEXT_FONT_SMALL = MP4FontManager.loadBaseFontOrFallback().deriveFont(Font.PLAIN, 9.0F);
    private static final int MARQUEE_GAP = 28;
    private static final int MARQUEE_HOLD_TICKS = 28;

    private final int portraitWidth;
    private final int portraitHeight;
    private final int landscapeWidth;
    private final int landscapeHeight;
    private final BufferedImage portraitTextImage;
    private final BufferedImage landscapeTextImage;
    private final BufferedImage measureImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    MP4GuiTextRenderer(int portraitWidth, int portraitHeight, int landscapeWidth, int landscapeHeight) {
        this.portraitWidth = portraitWidth;
        this.portraitHeight = portraitHeight;
        this.landscapeWidth = landscapeWidth;
        this.landscapeHeight = landscapeHeight;
        this.portraitTextImage = new BufferedImage(portraitWidth, portraitHeight, BufferedImage.TYPE_INT_ARGB);
        this.landscapeTextImage = new BufferedImage(landscapeWidth, landscapeHeight, BufferedImage.TYPE_INT_ARGB);
    }

    void drawText(byte[] pixels, int x, int y, String text, int color, boolean small, PixelBlender blender) {
        drawText(pixels, portraitTextImage, portraitWidth, portraitHeight, x, y, text, color, small, blender);
    }

    void drawTextCentered(byte[] pixels, int centerX, int y, String text, int color, boolean small,
            PixelBlender blender) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int width = textWidth(text, small);
        drawText(pixels, centerX - width / 2, y, text, color, small, blender);
    }

    void drawMarqueeText(byte[] pixels, int x, int y, int maxWidth, String text, int color, boolean small, int ticks,
            PixelBlender blender) {
        drawMarqueeText(pixels, portraitTextImage, portraitWidth, portraitHeight, x, y, maxWidth, text, color, small,
                ticks, blender);
    }

    void drawLandscapeText(byte[] pixels, int x, int y, String text, int color, boolean small, PixelBlender blender) {
        drawText(pixels, landscapeTextImage, landscapeWidth, landscapeHeight, x, y, text, color, small, blender);
    }

    void drawLandscapeTextCentered(byte[] pixels, int centerX, int y, String text, int color, boolean small,
            PixelBlender blender) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int width = textWidth(text, small);
        drawLandscapeText(pixels, centerX - width / 2, y, text, color, small, blender);
    }

    void drawLandscapeMarqueeText(byte[] pixels, int x, int y, int maxWidth, String text, int color, boolean small,
            int ticks, PixelBlender blender) {
        drawMarqueeText(pixels, landscapeTextImage, landscapeWidth, landscapeHeight, x, y, maxWidth, text, color, small,
                ticks, blender);
    }

    int textWidth(String text, boolean small) {
        Font font = small ? TEXT_FONT_SMALL : TEXT_FONT;
        Graphics2D graphics = measureImage.createGraphics();
        try {
            configurePixelText(graphics);
            graphics.setFont(font);
            return graphics.getFontMetrics().stringWidth(text);
        } finally {
            graphics.dispose();
        }
    }

    private void drawText(byte[] pixels, BufferedImage image, int imageWidth, int imageHeight, int x, int y,
            String text, int color, boolean small, PixelBlender blender) {
        if (text == null || text.isEmpty()) {
            return;
        }
        clearTextImage(image);
        int blendY;
        int blendHeight;
        int blendWidth;
        Graphics2D graphics = image.createGraphics();
        try {
            configurePixelText(graphics);
            graphics.setFont(small ? TEXT_FONT_SMALL : TEXT_FONT);
            graphics.setColor(new java.awt.Color(color, true));
            FontMetrics metrics = graphics.getFontMetrics();
            blendY = y - 1;
            blendHeight = metrics.getHeight() + 2;
            blendWidth = metrics.stringWidth(text) + 2;
            graphics.drawString(text, x, y + metrics.getAscent());
        } finally {
            graphics.dispose();
        }
        blendTextImage(pixels, image, imageWidth, imageHeight, x - 1, blendY, blendWidth, blendHeight, blender);
    }

    private void drawMarqueeText(byte[] pixels, BufferedImage image, int imageWidth, int imageHeight, int x, int y,
            int maxWidth, String text, int color, boolean small, int ticks, PixelBlender blender) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return;
        }
        int textWidth = textWidth(text, small);
        if (textWidth <= maxWidth) {
            drawText(pixels, image, imageWidth, imageHeight, x, y, text, color, small, blender);
            return;
        }
        clearTextImage(image);
        int blendY;
        int blendHeight;
        Graphics2D graphics = image.createGraphics();
        try {
            configurePixelText(graphics);
            graphics.setFont(small ? TEXT_FONT_SMALL : TEXT_FONT);
            graphics.setColor(new java.awt.Color(color, true));
            FontMetrics metrics = graphics.getFontMetrics();
            int cycle = textWidth + MARQUEE_GAP;
            int offset = marqueeOffset(cycle, ticks);
            graphics.setClip(x, y - 1, maxWidth, metrics.getHeight() + 2);
            int baseline = y + metrics.getAscent();
            blendY = y - 1;
            blendHeight = metrics.getHeight() + 2;
            graphics.drawString(text, x - offset, baseline);
            graphics.drawString(text, x - offset + cycle, baseline);
        } finally {
            graphics.dispose();
        }
        blendTextImage(pixels, image, imageWidth, imageHeight, x, blendY, maxWidth, blendHeight, blender);
    }

    private static int marqueeOffset(int cycle, int ticks) {
        int safeCycle = Math.max(1, cycle);
        int scrollTicks = safeCycle * 2;
        int tick = ticks % (MARQUEE_HOLD_TICKS * 2 + scrollTicks);
        if (tick < MARQUEE_HOLD_TICKS) {
            return 0;
        }
        if (tick >= MARQUEE_HOLD_TICKS + scrollTicks) {
            return safeCycle;
        }
        return (tick - MARQUEE_HOLD_TICKS) * safeCycle / scrollTicks;
    }

    private static void configurePixelText(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
    }

    private static void clearTextImage(BufferedImage image) {
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setComposite(java.awt.AlphaComposite.Clear);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
    }

    private static void blendTextImage(byte[] pixels, BufferedImage image, int imageWidth, int imageHeight, int x,
            int y,
            int w, int h, PixelBlender blender) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(imageWidth, x + Math.max(0, w));
        int y1 = Math.min(imageHeight, y + Math.max(0, h));
        for (int yy = y0; yy < y1; yy++) {
            for (int xx = x0; xx < x1; xx++) {
                int argb = image.getRGB(xx, yy);
                if (((argb >>> 24) & 0xFF) != 0) {
                    blender.blend(pixels, xx, yy, argb);
                }
            }
        }
    }

    @FunctionalInterface
    interface PixelBlender {
        void blend(byte[] pixels, int x, int y, int color);
    }
}
