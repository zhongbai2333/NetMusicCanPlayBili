package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapTileKind;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadPerfLogger;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Pad 显卡离屏界面渲染器，输出固定横屏 448x256 逻辑纹理。 */
final class PadOffscreenGuiRenderer implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SCALE = Integer.getInteger("ncpb.pad.offscreen_scale",
            Integer.getInteger("ncpb.mp4.offscreen_scale", 2));
    private static final int IDLE_REFRESH_TICKS = 2;
    private static final int FOCUSED_REFRESH_TICKS = 3;
    private static final int WIDTH = PadGuiTexture.WIDTH;
    private static final int HEIGHT = PadGuiTexture.HEIGHT;
    private static final int TARGET_WIDTH = WIDTH * Math.max(1, SCALE);
    private static final int TARGET_HEIGHT = HEIGHT * Math.max(1, SCALE);
    private static final long MAX_GUI_FPS = Long.getLong("ncpb.pad.gui_max_fps", 60L);
    private static final long MIN_FRAME_INTERVAL_NANOS = MAX_GUI_FPS <= 0L ? 0L : 1_000_000_000L / MAX_GUI_FPS;
    private static final float MAP_ASPECT = PadGuiTexture.WIDTH / (float) PadGuiTexture.HEIGHT;
    private final Identifier textureId;
    private TextureTarget target;
    private RenderTargetTexture texture;
    private GuiRenderer renderer;
    private GuiRenderState renderState;
    private ProjectionMatrixBuffer projectionBuffer;
    private SubmitNodeStorage submitNodeStorage;
    private FogRenderer fogRenderer;
    private boolean failed;
    private boolean loggedReady;
    private PadGuiViewState lastView;
    private int lastRenderedTick = Integer.MIN_VALUE;
    private long lastRenderedNanos;
    private long lastFocusRevision = Long.MIN_VALUE;
    private final PadMapLayerTexture mapLayer;

    PadOffscreenGuiRenderer(String textureKey) {
        String safeKey = textureKey == null || textureKey.isBlank() ? "fallback"
                : textureKey.toLowerCase(java.util.Locale.ROOT);
        this.textureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/pad_gui_offscreen_" + safeKey);
        this.mapLayer = new PadMapLayerTexture(safeKey);
    }

    Identifier textureId(UUID deviceId) {
        request(deviceId);
        return textureId;
    }

    void request(UUID deviceId) {
        if (failed) {
            return;
        }
        try {
            ensureResources();
        } catch (RuntimeException ex) {
            failed = true;
            LOGGER.warn("Pad 离屏 GUI 初始化失败: {}", textureId, ex);
        }
    }

    void tickMapLayer(UUID deviceId) {
        if (failed) {
            return;
        }
        try {
            PadGuiViewState view = PadGuiViewState.capture(deviceId);
            mapLayer.tick(view.map());
        } catch (RuntimeException ex) {
            failed = true;
            LOGGER.warn("Pad 地图离屏层 tick 更新失败: {}", textureId, ex);
        }
    }

    void renderFrameStart(UUID deviceId, float partialTick) {
        if (failed) {
            return;
        }
        try {
            ensureResources();
            PadGuiViewState view = PadGuiViewState.capture(deviceId, partialTick);
            long now = System.nanoTime();
            if (!shouldRender(view)) {
                return;
            }
            if (!shouldRenderNow(view, now)) {
                return;
            }
            long started = System.nanoTime();
            render(view);
            lastView = view;
            lastRenderedTick = view.ticks();
            lastFocusRevision = view.focusRevision();
            PadPerfLogger.recordGuiFrame(System.nanoTime() - started);
            if (!loggedReady) {
                loggedReady = true;
                LOGGER.info("Pad 离屏 GUI 已启用: texture={} target={}x{} logical={}x{} scale={}", textureId,
                        TARGET_WIDTH, TARGET_HEIGHT, WIDTH, HEIGHT, Math.max(1, SCALE));
            }
        } catch (RuntimeException ex) {
            failed = true;
            LOGGER.warn("Pad 离屏 GUI 渲染失败: {}", textureId, ex);
        }
    }

    private void ensureResources() {
        if (target != null) {
            return;
        }
        target = new TextureTarget("netmusic pad gui", TARGET_WIDTH, TARGET_HEIGHT, true);
        texture = new RenderTargetTexture(target);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getTextureManager().register(textureId, texture);
        renderState = new GuiRenderState();
        projectionBuffer = new ProjectionMatrixBuffer("netmusic_pad_offscreen_gui_projection");
        submitNodeStorage = new SubmitNodeStorage();
        fogRenderer = new FogRenderer();
        RenderBuffers buffers = minecraft.renderBuffers();
        renderer = new GuiRenderer(renderState, buffers.bufferSource(), submitNodeStorage,
                minecraft.gameRenderer.getFeatureRenderDispatcher(), List.of());
    }

    private boolean shouldRender(PadGuiViewState view) {
        boolean changed = lastView == null
                || view.focusRevision() != lastFocusRevision
                || view.document().sequence() != lastView.document().sequence()
                || view.document().mediaEntries().size() != lastView.document().mediaEntries().size()
                || view.document().triggerPoints().size() != lastView.document().triggerPoints().size()
                || view.map() != lastView.map()
                || Math.abs(view.playerYaw() - lastView.playerYaw()) >= 0.5F;
        int interval = com.zhongbai233.net_music_can_play_bili.client.PadFocusState.active()
                ? FOCUSED_REFRESH_TICKS
                : IDLE_REFRESH_TICKS;
        boolean expired = view.ticks() - lastRenderedTick >= interval;
        if (!changed && !expired) {
            return false;
        }
        return true;
    }

    private boolean shouldRenderNow(PadGuiViewState view, long now) {
        if (lastView == null || view.map() != lastView.map() || view.focusRevision() != lastFocusRevision) {
            return true;
        }
        return MIN_FRAME_INTERVAL_NANOS <= 0L || now - lastRenderedNanos >= MIN_FRAME_INTERVAL_NANOS;
    }

    private void render(PadGuiViewState view) {
        Minecraft minecraft = Minecraft.getInstance();
        renderState.reset();
        renderState.clearColorOverride = 0x00000000;
        GuiGraphicsExtractor graphics = new GuiGraphicsExtractor(minecraft, renderState, -1, -1);
        drawGui(graphics, view);

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (target.getDepthTexture() != null) {
            encoder.clearColorAndDepthTextures(target.getColorTexture(), 0x00000000, target.getDepthTexture(), 1.0D);
        } else {
            encoder.clearColorTexture(target.getColorTexture(), 0x00000000);
        }

        WindowRenderState windowState = minecraft.gameRenderer.getGameRenderState().windowRenderState;
        int oldWidth = windowState.width;
        int oldHeight = windowState.height;
        int oldGuiScale = windowState.guiScale;
        windowState.width = TARGET_WIDTH;
        windowState.height = TARGET_HEIGHT;
        windowState.guiScale = Math.max(1, SCALE);
        Matrix4f projection = new Matrix4f().setOrtho(0.0F, TARGET_WIDTH, TARGET_HEIGHT, 0.0F, 1000.0F, 3000.0F);
        GpuBufferSlice oldProjectionBuffer = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType oldProjectionType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        try {
            modelViewStack.identity();
            OffscreenGuiRenderTargetContext.withTarget(target,
                    () -> renderer.render(fogRenderer.getBuffer(FogRenderer.FogMode.NONE)));
            renderer.endFrame();
            submitNodeStorage.endFrame();
            fogRenderer.endFrame();
            texture.refreshView();
        } finally {
            modelViewStack.popMatrix();
            if (oldProjectionBuffer != null) {
                RenderSystem.setProjectionMatrix(oldProjectionBuffer, oldProjectionType);
            }
            windowState.width = oldWidth;
            windowState.height = oldHeight;
            windowState.guiScale = oldGuiScale;
            lastRenderedNanos = System.nanoTime();
        }
    }

    private void drawGui(GuiGraphicsExtractor g, PadGuiViewState view) {
        fill(g, 0, 0, WIDTH, HEIGHT, 0xFF071018);
        fill(g, 3, 3, WIDTH - 6, HEIGHT - 6, 0xFF101A26);
        drawStatusBar(g, view);
        drawMapCard(g, view, 14, 44, 270, 198);
        drawMediaLibrary(g, view, 300, 48, 132, 98);
        if (hasSelectedPoint(view)) {
            drawPointEditor(g, view, 300, 130, 132, 86);
        } else {
            drawPlaybackCard(g, view, 300, 166, 132, 42);
        }
        drawBottomHint(g, view);
        drawFocusFeedback(g);
        outline(g, 1, 1, WIDTH - 2, HEIGHT - 2, 0xFF02060A);
        outline(g, 5, 5, WIDTH - 10, HEIGHT - 10, 0x6637A9FF);
    }

    private void drawFocusFeedback(GuiGraphicsExtractor g) {
        drawControlFeedback(g, "MAP", 14, 44, 270, 198);
        drawControlFeedback(g, "MEDIA", 300, 48, 132, 98);
        drawControlFeedback(g, "EDITOR", 300, 130, 132, 86);
        drawControlFeedback(g, "PLAYBACK", 300, 166, 132, 42);
    }

    private void drawControlFeedback(GuiGraphicsExtractor g, String control, int x, int y, int w, int h) {
        if (com.zhongbai233.net_music_can_play_bili.client.PadFocusState.pressControl(control)) {
            outline(g, x - 2, y - 2, w + 4, h + 4, 0xFFFFD166);
            fill(g, x, y, w, h, 0x22FFD166);
        } else if (com.zhongbai233.net_music_can_play_bili.client.PadFocusState.hoverControl(control)) {
            outline(g, x - 1, y - 1, w + 2, h + 2, 0xAA89D6FF);
        }
    }

    private void drawStatusBar(GuiGraphicsExtractor g, PadGuiViewState view) {
        fill(g, 8, 8, WIDTH - 16, 26, 0xFF172638);
        text(g, 20, 16, "PAD MAP / 横屏", 0xFFBDE7FF);
        String right = view.document().locked() ? "LOCKED" : "DRAFT";
        text(g, WIDTH - 20 - Minecraft.getInstance().font.width(right), 16, right,
                view.document().locked() ? 0xFFFFC857 : 0xFF6FE28A);
    }

    private void drawMapCard(GuiGraphicsExtractor g, PadGuiViewState view, int x, int y, int w, int h) {
        fill(g, x, y, w, h, 0xFF1B2A38);
        outline(g, x, y, w, h, 0xFF2F4A60);
        int innerX = x + 6;
        int innerY = y + 6;
        int innerW = w - 12;
        int innerH = h - 12;
        MapRect mapRect = fitMapRect(innerX, innerY, innerW, innerH);
        PadMapSnapshot renderedMap = mapLayer.renderedSnapshotOr(view.map());
        MapViewport viewport = mapViewport(renderedMap, view, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
        blitClippedMap(g, mapLayer.textureId(view.map()), viewport, mapRect.x(), mapRect.y(), mapRect.w(),
                mapRect.h());
        drawMapOverlayGrid(g, renderedMap, viewport, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
        drawMapLegend(g, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
        drawPins(g, renderedMap, view, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
        drawPlayerLocation(g, view, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
        drawMapHoleFrame(g, x, y, w, h, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
    }

    private void blitClippedMap(GuiGraphicsExtractor g, Identifier texture, MapViewport viewport, int clipX, int clipY,
            int clipW, int clipH) {
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

    private MapRect fitMapRect(int x, int y, int w, int h) {
        if (PadMapLayerTexture.VIEW_WIDTH <= w && PadMapLayerTexture.VIEW_HEIGHT <= h) {
            int fittedX = x + (w - PadMapLayerTexture.VIEW_WIDTH) / 2;
            int fittedY = y + (h - PadMapLayerTexture.VIEW_HEIGHT) / 2;
            return new MapRect(fittedX, fittedY, PadMapLayerTexture.VIEW_WIDTH, PadMapLayerTexture.VIEW_HEIGHT);
        }
        int fittedW = w;
        int fittedH = Math.round(fittedW / MAP_ASPECT);
        if (fittedH > h) {
            fittedH = h;
            fittedW = Math.round(fittedH * MAP_ASPECT);
        }
        int fittedX = x + (w - fittedW) / 2;
        int fittedY = y + (h - fittedH) / 2;
        return new MapRect(fittedX, fittedY, fittedW, fittedH);
    }

    private void drawMapHoleFrame(GuiGraphicsExtractor g, int x, int y, int w, int h, int innerX, int innerY,
            int innerW, int innerH) {
        outline(g, x, y, w, h, 0xFF2F4A60);
        outline(g, innerX - 1, innerY - 1, innerW + 2, innerH + 2, 0x6637A9FF);
    }

    private void drawMapOverlayGrid(GuiGraphicsExtractor g, PadMapSnapshot map, MapViewport viewport, int clipX,
            int clipY, int clipW, int clipH) {
        float cell = Math.min(viewport.cellX(), viewport.cellY());
        int dash = Math.max(3, Math.round(cell * 2.4F));
        int gap = Math.max(4, Math.round(cell * 3.2F));
        int color = 0x55B8C5CD;
        float worldX0 = screenToWorldX(clipX, map, viewport);
        float worldX1 = screenToWorldX(clipX + clipW, map, viewport);
        float worldZ0 = screenToWorldZ(clipY, map, viewport);
        float worldZ1 = screenToWorldZ(clipY + clipH, map, viewport);
        int minWorldX = Math.floorDiv(Math.round(Math.min(worldX0, worldX1)), 16) * 16 - 16;
        int maxWorldX = Math.floorDiv(Math.round(Math.max(worldX0, worldX1)), 16) * 16 + 16;
        int minWorldZ = Math.floorDiv(Math.round(Math.min(worldZ0, worldZ1)), 16) * 16 - 16;
        int maxWorldZ = Math.floorDiv(Math.round(Math.max(worldZ0, worldZ1)), 16) * 16 + 16;
        for (int worldZ = minWorldZ; worldZ <= maxWorldZ; worldZ += 16) {
            int lineY = Math.round(mapScreenY(worldZ, map, viewport));
            if (lineY < clipY || lineY >= clipY + clipH) {
                continue;
            }
            int startX = clipX;
            int endX = clipX + clipW;
            int firstX = firstDashedPixel(startX, Math.round(mapScreenX(0.0F, map, viewport)), dash + gap);
            for (int px = firstX; px < endX; px += dash + gap) {
                fillClipped(g, px, lineY, Math.min(dash, endX - px), 1, color, clipX, clipY, clipW, clipH);
            }
        }
        for (int worldX = minWorldX; worldX <= maxWorldX; worldX += 16) {
            int lineX = Math.round(mapScreenX(worldX, map, viewport));
            if (lineX < clipX || lineX >= clipX + clipW) {
                continue;
            }
            int startY = clipY;
            int endY = clipY + clipH;
            int firstY = firstDashedPixel(startY, Math.round(mapScreenY(0.0F, map, viewport)), dash + gap);
            for (int py = firstY; py < endY; py += dash + gap) {
                fillClipped(g, lineX, py, 1, Math.min(dash, endY - py), color, clipX, clipY, clipW, clipH);
            }
        }
    }

    private int firstDashedPixel(int visibleStart, int worldAnchorPixel, int period) {
        int offset = Math.floorMod(visibleStart - worldAnchorPixel, Math.max(1, period));
        return visibleStart - offset;
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

    private MapViewport mapViewport(PadMapSnapshot map, PadGuiViewState view, int ox, int oy, int w, int h) {
        float cell = PadMapLayerTexture.CELL_PIXELS * Math.max(1.0F, map.displayScale());
        float drawW = map.width() * cell;
        float drawH = map.height() * cell;
        float centerX = ox + w / 2.0F;
        float centerY = oy + h / 2.0F;
        float offsetX = (view.playerX() - map.centerX()) / map.cellSizeBlocks() * cell;
        float offsetY = -(view.playerZ() - map.centerZ()) / map.cellSizeBlocks() * cell;
        float snappedX = Math.round(centerX - drawW / 2.0F + offsetX);
        float snappedY = Math.round(centerY - drawH / 2.0F - offsetY);
        float snappedW = Math.round(drawW);
        float snappedH = Math.round(drawH);
        return new MapViewport(snappedX, snappedY, snappedW, snappedH, cell, cell);
    }

    private float mapScreenX(float worldX, PadMapSnapshot map, MapViewport viewport) {
        return viewport.x() + viewport.w() / 2.0F - (worldX - map.centerX())
                / map.cellSizeBlocks() * viewport.cellX();
    }

    private float mapScreenY(float worldZ, PadMapSnapshot map, MapViewport viewport) {
        return viewport.y() + viewport.h() / 2.0F - (worldZ - map.centerZ())
                / map.cellSizeBlocks() * viewport.cellY();
    }

    private float screenToWorldX(float screenX, PadMapSnapshot map, MapViewport viewport) {
        return map.centerX() - (screenX - viewport.x() - viewport.w() / 2.0F)
                * map.cellSizeBlocks() / viewport.cellX();
    }

    private float screenToWorldZ(float screenY, PadMapSnapshot map, MapViewport viewport) {
        return map.centerZ() - (screenY - viewport.y() - viewport.h() / 2.0F)
                * map.cellSizeBlocks() / viewport.cellY();
    }

    private void drawPlayerLocation(GuiGraphicsExtractor g, PadGuiViewState view, int ox, int oy, int w, int h) {
        int px = ox + w / 2;
        int pz = oy + h / 2;
        drawLocationArrow(g, clamp(px, ox + 9, ox + w - 9), clamp(pz, oy + 9, oy + h - 9), view.playerYaw());
    }

    private void drawPins(GuiGraphicsExtractor g, PadMapSnapshot map, PadGuiViewState view, int ox, int oy, int w,
            int h) {
        MapViewport viewport = mapViewport(map, view, ox, oy, w, h);
        for (PadTriggerPoint point : view.document().triggerPoints()) {
            if (view.document().locked() && !point.visible()) {
                continue;
            }
            boolean draggingThis = point.pointId().equals(
                    com.zhongbai233.net_music_can_play_bili.client.PadFocusState.draggingPointId());
            int px = draggingThis
                    && com.zhongbai233.net_music_can_play_bili.client.PadFocusState.draggingPointTextureX() >= 0
                            ? com.zhongbai233.net_music_can_play_bili.client.PadFocusState.draggingPointTextureX()
                            : Math.round(mapScreenX((float) point.x(), map, viewport));
            int pz = draggingThis
                    && com.zhongbai233.net_music_can_play_bili.client.PadFocusState.draggingPointTextureY() >= 0
                            ? com.zhongbai233.net_music_can_play_bili.client.PadFocusState.draggingPointTextureY()
                            : Math.round(mapScreenY((float) point.z(), map, viewport));
            if (draggingThis) {
                fill(g, px - 12, pz - 17, 25, 31, 0x33FFD166);
                outline(g, px - 12, pz - 17, 25, 31, 0xFFFFD166);
            }
            drawPoi(g, px, pz, point.name().isBlank() ? "点位" : point.name(), point.visible(),
                    com.zhongbai233.net_music_can_play_bili.client.PadFocusState.selectedPoint(point.pointId()));
        }
        drawMediaDragPreview(g, ox, oy, w, h);
    }

    private void drawMediaDragPreview(GuiGraphicsExtractor g, int ox, int oy, int w, int h) {
        if (!com.zhongbai233.net_music_can_play_bili.client.PadFocusState.draggingMedia()
                || !com.zhongbai233.net_music_can_play_bili.client.PadFocusState.hoverControl("MAP")) {
            return;
        }
        int px = clamp(com.zhongbai233.net_music_can_play_bili.client.PadFocusState.hoverTextureX(), ox + 6,
                ox + w - 6);
        int pz = clamp(com.zhongbai233.net_music_can_play_bili.client.PadFocusState.hoverTextureY(), oy + 10,
                oy + h - 8);
        fill(g, px - 12, pz - 17, 25, 31, 0x44FFD166);
        outline(g, px - 12, pz - 17, 25, 31, 0xFFFFD166);
        drawPoi(g, px, pz, com.zhongbai233.net_music_can_play_bili.client.PadFocusState.draggingMediaName(), true,
                true);
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

    private record MapViewport(float x, float y, float w, float h, float cellX, float cellY) {
    }

    private record MapRect(int x, int y, int w, int h) {
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

    private void drawMediaLibrary(GuiGraphicsExtractor g, PadGuiViewState view, int x, int y, int w, int h) {
        fill(g, x, y, w, h, 0xFF111B27);
        outline(g, x, y, w, h, 0xFF2F4A60);
        text(g, x + 10, y + 10, "媒体库 " + view.document().mediaEntries().size(), 0xFFE8E0C8);
        text(g, x + 88, y + 10, "点位 " + view.document().triggerPoints().size(), 0xFFFFC857);
        if (view.document().mediaEntries().isEmpty()) {
            centeredText(g, x + w / 2, y + 52, "暂无歌曲", 0xFF7E8EA3);
            centeredText(g, x + w / 2, y + 68, "把唱片放到 Pad", 0xFF526173);
            return;
        }
        int row = 0;
        for (PadMediaEntry entry : view.document().mediaEntries()) {
            if (row >= 4) {
                break;
            }
            int rowY = y + 30 + row * 16;
            fill(g, x + 8, rowY, w - 16, 15, 0xFF1B2A38);
            String name = mediaName(entry);
            text(g, x + 14, rowY + 4, trim(name, 10), 0xFFEAF2FF);
            text(g, x + w - 32, rowY + 4, "#" + entry.mediaId(), 0xFF89D6FF);
            row++;
        }
        if (com.zhongbai233.net_music_can_play_bili.client.PadFocusState.draggingMedia()) {
            centeredText(g, x + w / 2, y + h - 15, "拖到地图创建点位", 0xFFFFD166);
        }
    }

    private void drawPointEditor(GuiGraphicsExtractor g, PadGuiViewState view, int x, int y, int w, int h) {
        Optional<PadTriggerPoint> selected = view.document().triggerPoints().stream()
                .filter(point -> com.zhongbai233.net_music_can_play_bili.client.PadFocusState
                        .selectedPoint(point.pointId()))
                .findFirst();
        if (selected.isEmpty()) {
            return;
        }
        PadTriggerPoint point = selected.get();
        fill(g, x, y, w, h, 0xEE0E1824);
        outline(g, x, y, w, h, 0xFFFFD166);
        text(g, x + 8, y + 7, trim(point.name().isBlank() ? "点位" : point.name(), 12), 0xFFFFF3C4);
        text(g, x + 8, y + 18,
                "R" + point.radiusBlocks() + " V" + point.volumePerMille() / 10 + "% #" + point.mediaId(),
                0xFF89D6FF);
        drawEditorButton(g, x + 8, y + 28, 52, 15, point.visible() ? "显示" : "隐藏",
                point.visible() ? 0xFFE84A5F : 0xFF7E8EA3);
        drawEditorButton(g, x + 66, y + 28, 58, 15, "删除", 0xFFFF6B6B);
        drawEditorButton(g, x + 8, y + 45, 28, 15, "R-", 0xFFBDE7FF);
        drawEditorButton(g, x + 40, y + 45, 28, 15, "R+", 0xFFBDE7FF);
        drawEditorButton(g, x + 74, y + 45, 50, 15, point.loop() ? "循环" : "单次", 0xFF6FE28A);
        drawEditorButton(g, x + 8, y + 62, 52, 15,
                point.triggerMode().name().equals("MANUAL") ? "手动" : "半径", 0xFFFFC857);
        drawEditorButton(g, x + 66, y + 62, 28, 15, "V-", 0xFFBDE7FF);
        drawEditorButton(g, x + 96, y + 62, 28, 15, "V+", 0xFFBDE7FF);
    }

    private boolean hasSelectedPoint(PadGuiViewState view) {
        return view.document().triggerPoints().stream()
                .anyMatch(point -> com.zhongbai233.net_music_can_play_bili.client.PadFocusState
                        .selectedPoint(point.pointId()));
    }

    private void drawEditorButton(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, int color) {
        fill(g, x, y, w, h, 0xFF1B2A38);
        outline(g, x, y, w, h, color);
        centeredText(g, x + w / 2, y + 4, label, color);
    }

    private void drawBottomHint(GuiGraphicsExtractor g, PadGuiViewState view) {
        fill(g, 300, 220, 132, 18, 0xFF172638);
        String hint = view.document().locked() ? "点击点位 / 右键关闭" : "拖歌或拖点 / 右键关闭";
        centeredText(g, 366, 225, hint, 0xFF89D6FF);
    }

    private String mediaName(PadMediaEntry entry) {
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(entry.disc());
        if (info != null && info.songName != null && !info.songName.isBlank()) {
            return info.songName;
        }
        return "歌曲 #" + entry.mediaId();
    }

    private String trim(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private void drawPlaybackCard(GuiGraphicsExtractor g, PadGuiViewState view, int x, int y, int w, int h) {
        fill(g, x, y, w, h, 0xFF111B27);
        outline(g, x, y, w, h, 0xFF2F4A60);
        centeredText(g, x + w / 2, y + 9, "Pad 播放目标", 0xFFE8E0C8);
        centeredText(g, x + w / 2, y + 25, "GUI 横屏播放", 0xFF89D6FF);
    }

    private void fill(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
    }

    private void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.outline(x, y, w, h, color);
    }

    private void text(GuiGraphicsExtractor g, int x, int y, String value, int color) {
        Font font = Minecraft.getInstance().font;
        g.text(font, value, x, y, color, false);
    }

    private void centeredText(GuiGraphicsExtractor g, int centerX, int y, String value, int color) {
        Font font = Minecraft.getInstance().font;
        g.text(font, Component.literal(value), centerX - font.width(value) / 2, y, color, false);
    }

    @Override
    public void close() {
        if (texture != null) {
            texture.close();
        }
        if (target != null) {
            target.destroyBuffers();
        }
        mapLayer.close();
    }
}