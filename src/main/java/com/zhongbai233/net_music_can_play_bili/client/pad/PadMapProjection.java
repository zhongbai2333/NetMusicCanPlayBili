package com.zhongbai233.net_music_can_play_bili.client.pad;

/**
 * Shared Pad map projection helpers.
 * <p>
 * The sampled/baked map texture is centered on the snapshot center, while the
 * displayed viewport may be panned around a live focus point (normally the
 * player's interpolated position). Keeping this math in one place avoids the
 * offscreen Pad GUI and the interactive Pad focus screen drifting apart.
 * </p>
 */
public final class PadMapProjection {
    private PadMapProjection() {
    }

    public static Viewport viewport(PadMapSnapshot map, Rect rect) {
        return viewport(map, rect, map != null ? map.centerX() : 0.0F, map != null ? map.centerZ() : 0.0F, 1.0F);
    }

    public static Viewport viewport(PadMapSnapshot map, Rect rect, double focusX, double focusZ) {
        return viewport(map, rect, (float) focusX, (float) focusZ, 1.0F);
    }

    public static Viewport viewport(PadMapSnapshot map, Rect rect, float focusX, float focusZ, float cellPixelScale) {
        if (map == null || rect == null) {
            return new Viewport(0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);
        }
        float cell = Math.max(0.01F, cellPixelScale) * Math.max(1.0F, map.displayScale());
        float drawW = map.width() * cell;
        float drawH = map.height() * cell;
        float centerX = rect.x() + rect.w() / 2.0F;
        float centerY = rect.y() + rect.h() / 2.0F;
        float offsetX = (focusX - map.centerX()) / map.cellSizeBlocks() * cell;
        float offsetY = -(focusZ - map.centerZ()) / map.cellSizeBlocks() * cell;
        return new Viewport(Math.round(centerX - drawW / 2.0F + offsetX),
                Math.round(centerY - drawH / 2.0F - offsetY), Math.round(drawW), Math.round(drawH), cell, cell);
    }

    public static Rect fitRect(int x, int y, int w, int h, int preferredW, int preferredH, float fallbackAspect) {
        if (preferredW > 0 && preferredH > 0 && preferredW <= w && preferredH <= h) {
            int fittedX = x + (w - preferredW) / 2;
            int fittedY = y + (h - preferredH) / 2;
            return new Rect(fittedX, fittedY, preferredW, preferredH);
        }
        float aspect = preferredW > 0 && preferredH > 0
                ? preferredW / (float) preferredH
                : Math.max(0.01F, fallbackAspect);
        int fittedW = w;
        int fittedH = Math.round(fittedW / aspect);
        if (fittedH > h) {
            fittedH = h;
            fittedW = Math.round(fittedH * aspect);
        }
        int fittedX = x + (w - fittedW) / 2;
        int fittedY = y + (h - fittedH) / 2;
        return new Rect(fittedX, fittedY, fittedW, fittedH);
    }

    public static float mapScreenX(float worldX, PadMapSnapshot map, Viewport viewport) {
        return viewport.x() + viewport.w() / 2.0F - (worldX - map.centerX())
                / map.cellSizeBlocks() * viewport.cellX();
    }

    public static float mapScreenY(float worldZ, PadMapSnapshot map, Viewport viewport) {
        return viewport.y() + viewport.h() / 2.0F - (worldZ - map.centerZ())
                / map.cellSizeBlocks() * viewport.cellY();
    }

    public static float screenToWorldX(float screenX, PadMapSnapshot map, Viewport viewport) {
        return map.centerX() - (screenX - viewport.x() - viewport.w() / 2.0F)
                * map.cellSizeBlocks() / viewport.cellX();
    }

    public static float screenToWorldZ(float screenY, PadMapSnapshot map, Viewport viewport) {
        return map.centerZ() - (screenY - viewport.y() - viewport.h() / 2.0F)
                * map.cellSizeBlocks() / viewport.cellY();
    }

    public record Rect(int x, int y, int w, int h) {
    }

    public record Viewport(float x, float y, float w, float h, float cellX, float cellY) {
    }
}
