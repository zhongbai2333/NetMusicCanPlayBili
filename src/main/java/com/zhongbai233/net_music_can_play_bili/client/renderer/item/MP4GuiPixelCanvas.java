package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

/**
 * MP4 图形界面纹理使用的底层竖屏/横屏像素绘制辅助工具。
 */
final class MP4GuiPixelCanvas {
    private final int width;
    private final int height;
    private final int landscapeWidth;
    private final int landscapeHeight;

    MP4GuiPixelCanvas(int width, int height, int landscapeWidth, int landscapeHeight) {
        this.width = width;
        this.height = height;
        this.landscapeWidth = landscapeWidth;
        this.landscapeHeight = landscapeHeight;
    }

    void clear(byte[] pixels, int color) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                setPixel(pixels, x, y, color);
            }
        }
    }

    void fillRect(byte[] pixels, int x, int y, int w, int h, int color) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w);
        int y1 = Math.min(height, y + h);
        for (int yy = y0; yy < y1; yy++) {
            for (int xx = x0; xx < x1; xx++) {
                setPixel(pixels, xx, yy, color);
            }
        }
    }

    void fillRectOutline(byte[] pixels, int x, int y, int w, int h, int color) {
        fillRect(pixels, x, y, w, 2, color);
        fillRect(pixels, x, y + h - 2, w, 2, color);
        fillRect(pixels, x, y, 2, h, color);
        fillRect(pixels, x + w - 2, y, 2, h, color);
    }

    void fillRoundRect(byte[] pixels, int x, int y, int w, int h, int radius, int color) {
        int r = Math.max(0, radius);
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                if (insideRoundRect(xx, yy, x, y, w, h, r)) {
                    setPixel(pixels, xx, yy, color);
                }
            }
        }
    }

    void fillRoundRectOutline(byte[] pixels, int x, int y, int w, int h, int radius, int color) {
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                if (insideRoundRect(xx, yy, x, y, w, h, radius)
                        && !insideRoundRect(xx, yy, x + 2, y + 2, w - 4, h - 4, Math.max(0, radius - 2))) {
                    blendPixel(pixels, xx, yy, color);
                }
            }
        }
    }

    void fillCircle(byte[] pixels, int cx, int cy, int r, int color) {
        int rr = r * r;
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                int dx = x - cx;
                int dy = y - cy;
                if (dx * dx + dy * dy <= rr) {
                    setPixel(pixels, x, y, color);
                }
            }
        }
    }

    void drawSteppedGradient(byte[] pixels, int x, int y, int w, int h, int top, int bottom, int steps) {
        int safeSteps = Math.max(1, steps);
        for (int yy = 0; yy < h; yy++) {
            int step = Math.min(safeSteps - 1, yy * safeSteps / Math.max(1, h));
            float t = safeSteps <= 1 ? 0.0F : step / (float) (safeSteps - 1);
            fillRect(pixels, x, y + yy, w, 1, lerpColor(top, bottom, t));
        }
    }

    void drawPixelNoise(byte[] pixels, int x, int y, int w, int h, int dark, int light) {
        for (int yy = y; yy < y + h; yy += 4) {
            for (int xx = x; xx < x + w; xx += 4) {
                int hash = (xx * 31 + yy * 17) & 7;
                if (hash == 0) {
                    blendPixel(pixels, xx, yy, light);
                } else if (hash == 1) {
                    blendPixel(pixels, xx, yy, dark);
                }
            }
        }
    }

    void drawArcSparkles(byte[] pixels, int cx, int cy, int r) {
        for (int i = 0; i < 28; i++) {
            double a = i * Math.PI * 2.0 / 28.0;
            int x = cx + (int) Math.round(Math.cos(a) * r);
            int y = cy + (int) Math.round(Math.sin(a) * r);
            blendPixel(pixels, x, y, i % 3 == 0 ? 0xAAFFFFFF : 0x668CCBFF);
        }
    }

    void setPixel(byte[] pixels, int x, int y, int color) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return;
        }
        int i = (y * width + x) * 4;
        pixels[i] = (byte) ((color >> 16) & 0xFF);
        pixels[i + 1] = (byte) ((color >> 8) & 0xFF);
        pixels[i + 2] = (byte) (color & 0xFF);
        pixels[i + 3] = (byte) ((color >>> 24) & 0xFF);
    }

    void blendPixel(byte[] pixels, int x, int y, int color) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return;
        }
        int i = (y * width + x) * 4;
        float a = ((color >>> 24) & 0xFF) / 255.0F;
        int sr = (color >> 16) & 0xFF;
        int sg = (color >> 8) & 0xFF;
        int sb = color & 0xFF;
        int dr = pixels[i] & 0xFF;
        int dg = pixels[i + 1] & 0xFF;
        int db = pixels[i + 2] & 0xFF;
        pixels[i] = (byte) Math.round(dr + (sr - dr) * a);
        pixels[i + 1] = (byte) Math.round(dg + (sg - dg) * a);
        pixels[i + 2] = (byte) Math.round(db + (sb - db) * a);
        pixels[i + 3] = (byte) 255;
    }

    void fillLandscapeRect(byte[] pixels, int x, int y, int w, int h, int color) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(landscapeWidth, x + w);
        int y1 = Math.min(landscapeHeight, y + h);
        for (int yy = y0; yy < y1; yy++) {
            for (int xx = x0; xx < x1; xx++) {
                setLandscapePixel(pixels, xx, yy, color);
            }
        }
    }

    void fillLandscapeRectOutline(byte[] pixels, int x, int y, int w, int h, int color) {
        fillLandscapeRect(pixels, x, y, w, 2, color);
        fillLandscapeRect(pixels, x, y + h - 2, w, 2, color);
        fillLandscapeRect(pixels, x, y, 2, h, color);
        fillLandscapeRect(pixels, x + w - 2, y, 2, h, color);
    }

    void fillLandscapeRoundRect(byte[] pixels, int x, int y, int w, int h, int radius, int color) {
        int r = Math.max(0, radius);
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                if (insideRoundRect(xx, yy, x, y, w, h, r)) {
                    setLandscapePixel(pixels, xx, yy, color);
                }
            }
        }
    }

    void fillLandscapeCircle(byte[] pixels, int cx, int cy, int r, int color) {
        int rr = r * r;
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                int dx = x - cx;
                int dy = y - cy;
                if (dx * dx + dy * dy <= rr) {
                    setLandscapePixel(pixels, x, y, color);
                }
            }
        }
    }

    void drawLandscapeSteppedGradient(byte[] pixels, int x, int y, int w, int h, int top, int bottom, int steps) {
        int safeSteps = Math.max(1, steps);
        for (int yy = 0; yy < h; yy++) {
            int step = Math.min(safeSteps - 1, yy * safeSteps / Math.max(1, h));
            float t = safeSteps <= 1 ? 0.0F : step / (float) (safeSteps - 1);
            fillLandscapeRect(pixels, x, y + yy, w, 1, lerpColor(top, bottom, t));
        }
    }

    void setLandscapePixel(byte[] pixels, int x, int y, int color) {
        if (x < 0 || y < 0 || x >= landscapeWidth || y >= landscapeHeight) {
            return;
        }
        setPixel(pixels, y, height - 1 - x, color);
    }

    void blendLandscapePixel(byte[] pixels, int x, int y, int color) {
        if (x < 0 || y < 0 || x >= landscapeWidth || y >= landscapeHeight) {
            return;
        }
        blendPixel(pixels, y, height - 1 - x, color);
    }

    int lerpColor(int from, int to, float t) {
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static boolean insideRoundRect(int px, int py, int x, int y, int w, int h, int r) {
        int cx = px < x + r ? x + r : px >= x + w - r ? x + w - r - 1 : px;
        int cy = py < y + r ? y + r : py >= y + h - r ? y + h - r - 1 : py;
        int dx = px - cx;
        int dy = py - cy;
        return dx * dx + dy * dy <= r * r;
    }
}
