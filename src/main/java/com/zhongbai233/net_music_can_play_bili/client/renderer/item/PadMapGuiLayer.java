package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapProjection;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapTileKind;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** GPU-side Pad map composition helpers for the offscreen Pad GUI pass. */
final class PadMapGuiLayer {
    private final PadMapLayerTexture mapLayer;

    PadMapGuiLayer(PadMapLayerTexture mapLayer) {
        this.mapLayer = mapLayer;
    }

    void draw(GuiGraphicsExtractor g, PadGuiViewState view, PadMapProjection.Rect mapRect) {
        PadMapSnapshot renderedMap = mapLayer.renderedSnapshotOr(view.map());
        PadMapProjection.Viewport viewport = PadMapProjection.viewport(renderedMap, mapRect,
                view.playerX(), view.playerZ(), PadMapLayerTexture.CELL_PIXELS);
        blitClippedMap(g, mapLayer.textureId(view.map()), viewport, mapRect.x(), mapRect.y(), mapRect.w(),
                mapRect.h());
        drawMapOverlayGrid(g, renderedMap, viewport, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
        drawMapLegend(g, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
        drawPins(g, renderedMap, view, viewport, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
        drawPlayerLocation(g, view, renderedMap, viewport, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
    }

    private void blitClippedMap(GuiGraphicsExtractor g, Identifier texture, PadMapProjection.Viewport viewport,
            int clipX, int clipY, int clipW, int clipH) {
        float left = Math.max(viewport.x(), clipX);
        float top = Math.max(viewport.y(), clipY);
        float right = Math.min(viewport.x() + viewport.w(), clipX + clipW);
        float bottom = Math.min(viewport.y() + viewport.h(), clipY + clipH);
        if (right <= left || bottom <= top) {
            return;
        }
        float u0 = (left - viewport.x()) / viewport.w();
        float u1 = (right - viewport.x()) / viewport.w();
        float vTop = (top - viewport.y()) / viewport.h();
        float vBottom = (bottom - viewport.y()) / viewport.h();
        g.blit(texture, Math.round(left), Math.round(top), Math.round(right), Math.round(bottom), u0, u1,
                1.0F - vTop, 1.0F - vBottom);
    }

    private void drawMapOverlayGrid(GuiGraphicsExtractor g, PadMapSnapshot map, PadMapProjection.Viewport viewport,
            int clipX, int clipY, int clipW, int clipH) {
        float cell = Math.min(viewport.cellX(), viewport.cellY());
        float chunkPixels = cell * 16.0F / Math.max(1, map.cellSizeBlocks());
        if (chunkPixels < 4.0F) {
            return;
        }
        int color = 0x3DB8C5CD;
        float worldX0 = PadMapProjection.screenToWorldX(clipX, map, viewport);
        float worldX1 = PadMapProjection.screenToWorldX(clipX + clipW, map, viewport);
        float worldZ0 = PadMapProjection.screenToWorldZ(clipY, map, viewport);
        float worldZ1 = PadMapProjection.screenToWorldZ(clipY + clipH, map, viewport);
        int minWorldX = ceilToChunk(Math.min(worldX0, worldX1));
        int maxWorldX = floorToChunk(Math.max(worldX0, worldX1));
        int minWorldZ = ceilToChunk(Math.min(worldZ0, worldZ1));
        int maxWorldZ = floorToChunk(Math.max(worldZ0, worldZ1));
        for (int worldZ = minWorldZ; worldZ <= maxWorldZ; worldZ += 16) {
            int lineY = Math.round(PadMapProjection.mapScreenY(worldZ, map, viewport));
            if (lineY < clipY || lineY >= clipY + clipH) {
                continue;
            }
            fillClipped(g, clipX, lineY, clipW, 1, color, clipX, clipY, clipW, clipH);
        }
        for (int worldX = minWorldX; worldX <= maxWorldX; worldX += 16) {
            int lineX = Math.round(PadMapProjection.mapScreenX(worldX, map, viewport));
            if (lineX < clipX || lineX >= clipX + clipW) {
                continue;
            }
            fillClipped(g, lineX, clipY, 1, clipH, color, clipX, clipY, clipW, clipH);
        }
    }

    private int ceilToChunk(float world) {
        return (int) Math.ceil(world / 16.0F) * 16;
    }

    private int floorToChunk(float world) {
        return (int) Math.floor(world / 16.0F) * 16;
    }

    private void drawMapLegend(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        PadMapTileKind[] kinds = { PadMapTileKind.WATER, PadMapTileKind.GRASS, PadMapTileKind.INDOOR_FLOOR,
                PadMapTileKind.TREE, PadMapTileKind.BUILDING, PadMapTileKind.FARMLAND, PadMapTileKind.ROCK };
        int rowH = 9;
        int boxW = 54;
        int boxH = kinds.length * rowH + 7;
        int lx = x + 5;
        int ly = y + h - boxH - 5;
        fill(g, lx, ly, boxW, boxH, 0x7A102033);
        outline(g, lx, ly, boxW, boxH, 0x6658B9E8);
        for (int i = 0; i < kinds.length; i++) {
            PadMapTileKind kind = kinds[i];
            int cy = ly + 4 + i * rowH;
            drawLegendSwatch(g, kind, lx + 4, cy + 2);
            String label = kind.label();
            text(g, lx + 13, cy, label, 0xEED9F4FF);
        }
    }

    private void drawLegendSwatch(GuiGraphicsExtractor g, PadMapTileKind kind, int x, int y) {
        fill(g, x, y, 5, 5, kind.color());
        outline(g, x - 1, y - 1, 7, 7, 0x99FFFFFF);
    }

    private void drawPlayerLocation(GuiGraphicsExtractor g, PadGuiViewState view, PadMapSnapshot map,
            PadMapProjection.Viewport viewport, int ox, int oy, int w, int h) {
        int px = Math.round(PadMapProjection.mapScreenX(view.playerX(), map, viewport));
        int pz = Math.round(PadMapProjection.mapScreenY(view.playerZ(), map, viewport));
        drawLocationArrow(g, clamp(px, ox + 9, ox + w - 9), clamp(pz, oy + 9, oy + h - 9), view.playerYaw());
    }

    private void drawPins(GuiGraphicsExtractor g, PadMapSnapshot map, PadGuiViewState view,
            PadMapProjection.Viewport viewport, int ox, int oy, int w, int h) {
        for (PadTriggerPoint point : view.document().triggerPoints()) {
            if (view.document().locked() && !point.visible()) {
                continue;
            }
            boolean draggingThis = point.pointId().equals(PadFocusState.draggingPointId());
            int px = draggingThis && PadFocusState.draggingPointTextureX() >= 0
                    ? PadFocusState.draggingPointTextureX()
                    : Math.round(PadMapProjection.mapScreenX((float) point.x(), map, viewport));
            int pz = draggingThis && PadFocusState.draggingPointTextureY() >= 0
                    ? PadFocusState.draggingPointTextureY()
                    : Math.round(PadMapProjection.mapScreenY((float) point.z(), map, viewport));
            if (draggingThis) {
                fill(g, px - 12, pz - 17, 25, 31, 0x33FFD166);
                outline(g, px - 12, pz - 17, 25, 31, 0xFFFFD166);
            }
            drawPoi(g, px, pz, point.name().isBlank() ? "点位" : point.name(), point.visible(),
                    PadFocusState.selectedPoint(point.pointId()));
        }
        drawMediaDragPreview(g, ox, oy, w, h);
    }

    private void drawMediaDragPreview(GuiGraphicsExtractor g, int ox, int oy, int w, int h) {
        if (!PadFocusState.draggingMedia() || !PadFocusState.hoverControl("MAP")) {
            return;
        }
        int px = clamp(PadFocusState.hoverTextureX(), ox + 6, ox + w - 6);
        int pz = clamp(PadFocusState.hoverTextureY(), oy + 10, oy + h - 8);
        fill(g, px - 12, pz - 17, 25, 31, 0x44FFD166);
        outline(g, px - 12, pz - 17, 25, 31, 0xFFFFD166);
        drawPoi(g, px, pz, PadFocusState.draggingMediaName(), true, true);
    }

    private void drawPoi(GuiGraphicsExtractor g, int x, int y, String label, boolean visible, boolean selected) {
        int fillColor = visible ? 0xFFE84A5F : 0x887E8EA3;
        int textColor = visible ? 0xFFE84A5F : 0xFF9CA8B6;
        if (selected) {
            outline(g, x - 9, y - 14, 19, 25, 0xFFFFD166);
            fill(g, x - 8, y - 13, 17, 23, 0x33FFD166);
        }
        fill(g, x - 6, y - 11, 13, 13, 0xFFFFFFFF);
        fill(g, x - 4, y - 9, 9, 9, fillColor);
        fill(g, x - 1, y + 1, 3, 7, fillColor);
        Font font = Minecraft.getInstance().font;
        String text = label.length() > 5 ? label.substring(0, 5) : label;
        int w = font.width(text);
        fill(g, x + 8, y - 10, w + 6, 12, 0xAAEDF2F5);
        g.text(font, text, x + 11, y - 8, textColor, false);
    }

    private void drawLocationArrow(GuiGraphicsExtractor g, int x, int y, float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        float forwardX = (float) Math.sin(yaw);
        float forwardY = (float) -Math.cos(yaw);
        float rightX = -forwardY;
        float rightY = forwardX;
        drawNavigationArrow(g, x, y, forwardX, forwardY, rightX, rightY, 7.6F, 5.4F, 3.2F, 0xFFFFFFFF);
        drawNavigationArrow(g, x, y, forwardX, forwardY, rightX, rightY, 6.1F, 4.0F, 2.4F, 0xFF2388FF);
        fillTriangle(g, x - forwardX * 0.6F - rightX * 1.3F, y - forwardY * 0.6F - rightY * 1.3F,
                x - forwardX * 0.6F + rightX * 1.3F, y - forwardY * 0.6F + rightY * 1.3F,
                x - forwardX * 3.3F, y - forwardY * 3.3F, 0xFF1557C4);
    }

    private void drawNavigationArrow(GuiGraphicsExtractor g, int x, int y, float forwardX, float forwardY,
            float rightX, float rightY, float length, float halfWidth, float notchDepth, int color) {
        float tipX = x + forwardX * length;
        float tipY = y + forwardY * length;
        float baseCenterX = x - forwardX * length * 0.62F;
        float baseCenterY = y - forwardY * length * 0.62F;
        float leftX = baseCenterX - rightX * halfWidth;
        float leftY = baseCenterY - rightY * halfWidth;
        float rightBaseX = baseCenterX + rightX * halfWidth;
        float rightBaseY = baseCenterY + rightY * halfWidth;
        float notchX = x - forwardX * notchDepth;
        float notchY = y - forwardY * notchDepth;
        fillTriangle(g, tipX, tipY, leftX, leftY, notchX, notchY, color);
        fillTriangle(g, tipX, tipY, notchX, notchY, rightBaseX, rightBaseY, color);
    }

    private void fillTriangle(GuiGraphicsExtractor g, float ax, float ay, float bx, float by, float cx, float cy,
            int color) {
        int minX = (int) Math.floor(Math.min(ax, Math.min(bx, cx)));
        int maxX = (int) Math.ceil(Math.max(ax, Math.max(bx, cx)));
        int minY = (int) Math.floor(Math.min(ay, Math.min(by, cy)));
        int maxY = (int) Math.ceil(Math.max(ay, Math.max(by, cy)));
        for (int py = minY; py <= maxY; py++) {
            int runStart = Integer.MIN_VALUE;
            for (int px = minX; px <= maxX; px++) {
                boolean inside = pointInTriangle(px + 0.5F, py + 0.5F, ax, ay, bx, by, cx, cy);
                if (inside && runStart == Integer.MIN_VALUE) {
                    runStart = px;
                } else if (!inside && runStart != Integer.MIN_VALUE) {
                    fill(g, runStart, py, px - runStart, 1, color);
                    runStart = Integer.MIN_VALUE;
                }
            }
            if (runStart != Integer.MIN_VALUE) {
                fill(g, runStart, py, maxX - runStart + 1, 1, color);
            }
        }
    }

    private boolean pointInTriangle(float px, float py, float ax, float ay, float bx, float by, float cx, float cy) {
        float d1 = sign(px, py, ax, ay, bx, by);
        float d2 = sign(px, py, bx, by, cx, cy);
        float d3 = sign(px, py, cx, cy, ax, ay);
        boolean hasNeg = d1 < 0.0F || d2 < 0.0F || d3 < 0.0F;
        boolean hasPos = d1 > 0.0F || d2 > 0.0F || d3 > 0.0F;
        return !(hasNeg && hasPos);
    }

    private float sign(float px, float py, float ax, float ay, float bx, float by) {
        return (px - bx) * (ay - by) - (ax - bx) * (py - by);
    }

    private void fillClipped(GuiGraphicsExtractor g, int x, int y, int w, int h, int color, int clipX, int clipY,
            int clipW, int clipH) {
        int left = Math.max(x, clipX);
        int top = Math.max(y, clipY);
        int right = Math.min(x + Math.max(0, w), clipX + clipW);
        int bottom = Math.min(y + Math.max(0, h), clipY + clipH);
        if (right > left && bottom > top) {
            fill(g, left, top, right - left, bottom - top, color);
        }
    }

    private void fill(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
    }

    private void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.outline(x, y, w, h, color);
    }

    private void text(GuiGraphicsExtractor g, int x, int y, String value, int color) {
        g.text(Minecraft.getInstance().font, value, x, y, color, false);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
