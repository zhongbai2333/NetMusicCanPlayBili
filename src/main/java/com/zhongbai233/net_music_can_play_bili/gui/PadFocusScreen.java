package com.zhongbai233.net_music_can_play_bili.gui;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSampler;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerMode;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.UUID;

/** Pad 自定义几何表面的透明输入层。 */
public class PadFocusScreen extends Screen {
    private static final int TEXTURE_W = 448;
    private static final int TEXTURE_H = 256;
    private static final int MAP_X = 14;
    private static final int MAP_Y = 44;
    private static final int MAP_W = 270;
    private static final int MAP_H = 198;
    private static final int MEDIA_X = 300;
    private static final int MEDIA_Y = 48;
    private static final int MEDIA_W = 132;
    private static final int MEDIA_H = 98;
    private static final int EDITOR_X = 300;
    private static final int EDITOR_Y = 130;
    private static final int EDITOR_W = 132;
    private static final int EDITOR_H = 86;
    private final InteractionHand hand;

    public PadFocusScreen(InteractionHand hand) {
        super(Component.translatable("gui.net_music_can_play_bili.pad.focus"));
        this.hand = hand;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        PadFocusState.activate(hand);
    }

    @Override
    public void onClose() {
        PadFocusState.deactivate();
        super.onClose();
    }

    @Override
    public void tick() {
        // 和 MP4 一样，手持动画由渲染帧推进；这里保留屏幕生命周期即可。
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        updateHover(mouseX, mouseY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        updateHover(mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean cancelled) {
        if (cancelled) {
            return false;
        }
        updateHover((int) event.x(), (int) event.y());
        if (event.button() == 1) {
            TexturePoint point = toTexturePoint((int) event.x(), (int) event.y());
            if (point != null && inside(point, MAP_X, MAP_Y, MAP_W, MAP_H)) {
                PadTriggerPoint hitPoint = pointAt(point);
                PadDocument document = PadItem.readDocument(heldPadStack());
                if (hitPoint != null && !document.locked()) {
                    removePoint(hitPoint.pointId());
                    return true;
                }
            }
            onClose();
            return true;
        }
        if (event.button() == 0) {
            handleLeftClick((int) event.x(), (int) event.y());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        updateHover((int) event.x(), (int) event.y());
        if (event.button() == 0 && PadFocusState.draggingPoint()) {
            TexturePoint point = toTexturePoint((int) event.x(), (int) event.y());
            if (point != null && inside(point, MAP_X, MAP_Y, MAP_W, MAP_H)) {
                PadFocusState.updatePointDragPreview(point.x(), point.y());
            }
            return true;
        }
        return event.button() == 0 || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        updateHover((int) event.x(), (int) event.y());
        if (event.button() == 0 && PadFocusState.draggingMedia()) {
            TexturePoint point = toTexturePoint((int) event.x(), (int) event.y());
            if (point != null && inside(point, MAP_X, MAP_Y, MAP_W, MAP_H)) {
                createPointFromDrag(point, PadFocusState.draggingMediaId(), PadFocusState.draggingMediaName());
            }
            PadFocusState.endMediaDrag();
            return true;
        }
        if (event.button() == 0 && PadFocusState.draggingPoint()) {
            TexturePoint point = toTexturePoint((int) event.x(), (int) event.y());
            if (point != null && inside(point, MAP_X, MAP_Y, MAP_W, MAP_H)) {
                moveSelectedPoint(point);
            }
            PadFocusState.endPointDrag();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        updateHover((int) mouseX, (int) mouseY);
        return true;
    }

    private void updateHover(int mouseX, int mouseY) {
        TexturePoint point = toTexturePoint(mouseX, mouseY);
        if (point == null) {
            PadFocusState.clearHoverTarget();
            return;
        }
        float localX = point.x() / (TEXTURE_W - 1.0F) * 2.0F - 1.0F;
        float localY = point.y() / (TEXTURE_H - 1.0F) * 2.0F - 1.0F;
        PadFocusState.updateHover(localX, localY);
        PadFocusState.updateHoverTarget(point.x(), point.y(), hit(point));
    }

    private void handleLeftClick(int mouseX, int mouseY) {
        TexturePoint point = toTexturePoint(mouseX, mouseY);
        if (point == null) {
            return;
        }
        String control = hit(point);
        PadFocusState.pressFeedback(point.x(), point.y(), control);
        if ("MEDIA".equals(control)) {
            PadMediaEntry entry = mediaEntryAt(point);
            if (entry != null) {
                PadFocusState.beginMediaDrag(entry.mediaId(), mediaName(entry));
            }
            return;
        }
        if ("MAP".equals(control)) {
            PadTriggerPoint hitPoint = pointAt(point);
            if (hitPoint != null) {
                PadFocusState.beginPointDrag(hitPoint.pointId());
            } else {
                PadFocusState.selectPoint(null);
            }
            return;
        }
        if ("EDITOR".equals(control)) {
            handleEditorClick(point);
        }
    }

    private void handleEditorClick(TexturePoint point) {
        PadDocument document = PadItem.readDocument(heldPadStack());
        if (document.locked()) {
            return;
        }
        PadTriggerPoint selected = selectedPoint(document);
        if (selected == null) {
            return;
        }
        int localX = point.x() - EDITOR_X;
        int localY = point.y() - EDITOR_Y;
        if (localY >= 28 && localY < 43 && localX >= 8 && localX < 60) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks(), !selected.visible(), selected.volumePerMille(),
                    selected.loop()));
            return;
        }
        if (localY >= 28 && localY < 43 && localX >= 66 && localX < 124) {
            removePoint(selected.pointId());
            return;
        }
        if (localY >= 45 && localY < 60 && localX >= 8 && localX < 36) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks() - 1, selected.visible(),
                    selected.volumePerMille(), selected.loop()));
            return;
        }
        if (localY >= 45 && localY < 60 && localX >= 40 && localX < 68) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks() + 1, selected.visible(),
                    selected.volumePerMille(), selected.loop()));
            return;
        }
        if (localY >= 45 && localY < 60 && localX >= 74 && localX < 124) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks(), selected.visible(), selected.volumePerMille(),
                    !selected.loop()));
            return;
        }
        if (localY >= 62 && localY < 77 && localX >= 8 && localX < 60) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks(), selected.visible(), selected.volumePerMille(),
                    selected.loop(), selected.triggerMode() == PadTriggerMode.MANUAL
                            ? PadTriggerMode.ENTER_RADIUS
                            : PadTriggerMode.MANUAL));
            return;
        }
        if (localY >= 62 && localY < 77 && localX >= 66 && localX < 94) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks(), selected.visible(),
                    selected.volumePerMille() - 100, selected.loop()));
            return;
        }
        if (localY >= 62 && localY < 77 && localX >= 96 && localX < 124) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks(), selected.visible(),
                    selected.volumePerMille() + 100, selected.loop()));
        }
    }

    private void createPointFromDrag(TexturePoint point, int mediaId, String mediaName) {
        ItemStack stack = heldPadStack();
        if (!PadItem.isPad(stack)) {
            return;
        }
        PadDocument document = PadItem.readDocument(stack);
        if (document.locked() || document.triggerPoints().size() >= PadDocument.MAX_TRIGGER_POINTS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        PadMapSnapshot map = PadMapClientCache.snapshot(minecraft.player.blockPosition().getX(),
                minecraft.player.blockPosition().getZ());
        MapRect mapRect = visibleMapRect();
        MapViewport viewport = mapViewport(map, mapRect, minecraft.player.getX(), minecraft.player.getZ());
        float worldX = screenToWorldX(point.x(), map, viewport);
        float worldZ = screenToWorldZ(point.y(), map, viewport);
        PadTriggerPoint created = PadTriggerPoint.createManual(mediaName, worldX, minecraft.player.getY(), worldZ,
                mediaId);
        PadItem.writeDocument(stack, document.withTrigger(created));
        PadFocusState.selectPoint(created.pointId());
        PadFocusState.pressFeedback(point.x(), point.y(), "MAP");
    }

    private void moveSelectedPoint(TexturePoint point) {
        ItemStack stack = heldPadStack();
        PadDocument document = PadItem.readDocument(stack);
        if (!PadItem.isPad(stack) || document.locked()) {
            return;
        }
        PadTriggerPoint selected = selectedPoint(document);
        if (selected == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        PadMapSnapshot map = PadMapClientCache.snapshot(minecraft.player.blockPosition().getX(),
                minecraft.player.blockPosition().getZ());
        MapViewport viewport = mapViewport(map, visibleMapRect(), minecraft.player.getX(), minecraft.player.getZ());
        PadItem.writeDocument(stack, document.withTrigger(new PadTriggerPoint(selected.pointId(), selected.name(),
                screenToWorldX(point.x(), map, viewport), selected.y(), screenToWorldZ(point.y(), map, viewport),
                selected.radiusBlocks(), selected.mediaId(), selected.triggerMode(), selected.loop(),
                selected.volumePerMille(), selected.visible())));
    }

    private PadTriggerPoint pointAt(TexturePoint point) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return null;
        }
        PadDocument document = PadItem.readDocument(heldPadStack());
        PadMapSnapshot map = PadMapClientCache.snapshot(minecraft.player.blockPosition().getX(),
                minecraft.player.blockPosition().getZ());
        MapViewport viewport = mapViewport(map, visibleMapRect(), minecraft.player.getX(), minecraft.player.getZ());
        for (int i = document.triggerPoints().size() - 1; i >= 0; i--) {
            PadTriggerPoint trigger = document.triggerPoints().get(i);
            if (document.locked() && !trigger.visible()) {
                continue;
            }
            int px = Math.round(mapScreenX((float) trigger.x(), map, viewport));
            int pz = Math.round(mapScreenY((float) trigger.z(), map, viewport));
            if (Math.abs(point.x() - px) <= 10 && Math.abs(point.y() - pz) <= 14) {
                return trigger;
            }
        }
        return null;
    }

    private PadTriggerPoint selectedPoint(PadDocument document) {
        UUID selected = PadFocusState.selectedPointId();
        if (selected == null) {
            return null;
        }
        return document.triggerPoints().stream().filter(point -> selected.equals(point.pointId())).findFirst()
                .orElse(null);
    }

    private void updatePoint(PadTriggerPoint point) {
        ItemStack stack = heldPadStack();
        PadDocument document = PadItem.readDocument(stack);
        if (!PadItem.isPad(stack) || document.locked()) {
            return;
        }
        PadItem.writeDocument(stack, document.withTrigger(point));
        PadFocusState.selectPoint(point.pointId());
    }

    private void removePoint(UUID pointId) {
        ItemStack stack = heldPadStack();
        PadDocument document = PadItem.readDocument(stack);
        if (!PadItem.isPad(stack) || document.locked() || pointId == null) {
            return;
        }
        ArrayList<PadTriggerPoint> points = new ArrayList<>(document.triggerPoints());
        if (points.removeIf(point -> pointId.equals(point.pointId()))) {
            PadItem.writeDocument(stack, new PadDocument(document.title(), document.author(), document.locked(),
                    System.currentTimeMillis(), document.sequence() + 1, document.mapSettings(),
                    document.mediaEntries(), points));
            PadFocusState.selectPoint(null);
        }
    }

    private PadTriggerPoint rebuildPoint(PadTriggerPoint point, int radius, boolean visible, int volume, boolean loop) {
        return rebuildPoint(point, radius, visible, volume, loop, point.triggerMode());
    }

    private PadTriggerPoint rebuildPoint(PadTriggerPoint point, int radius, boolean visible, int volume, boolean loop,
            PadTriggerMode mode) {
        return new PadTriggerPoint(point.pointId(), point.name(), point.x(), point.y(), point.z(), radius,
                point.mediaId(), mode, loop, volume, visible);
    }

    private PadMediaEntry mediaEntryAt(TexturePoint point) {
        int localY = point.y() - MEDIA_Y;
        if (localY < 30) {
            return null;
        }
        int row = (localY - 30) / 16;
        if (row < 0 || row >= 4) {
            return null;
        }
        PadDocument document = PadItem.readDocument(heldPadStack());
        return row < document.mediaEntries().size() ? document.mediaEntries().get(row) : null;
    }

    private String mediaName(PadMediaEntry entry) {
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(entry.disc());
        if (info != null && info.songName != null && !info.songName.isBlank()) {
            return info.songName;
        }
        return "歌曲 #" + entry.mediaId();
    }

    private ItemStack heldPadStack() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = minecraft.player.getItemInHand(hand);
        return PadItem.isPad(stack) ? stack : ItemStack.EMPTY;
    }

    private MapRect visibleMapRect() {
        return fitMapRect(MAP_X + 6, MAP_Y + 6, MAP_W - 12, MAP_H - 12);
    }

    private MapRect fitMapRect(int x, int y, int w, int h) {
        if (PadMapSampler.DEFAULT_VIEW_WIDTH <= w && PadMapSampler.DEFAULT_VIEW_HEIGHT <= h) {
            int fittedX = x + (w - PadMapSampler.DEFAULT_VIEW_WIDTH) / 2;
            int fittedY = y + (h - PadMapSampler.DEFAULT_VIEW_HEIGHT) / 2;
            return new MapRect(fittedX, fittedY, PadMapSampler.DEFAULT_VIEW_WIDTH, PadMapSampler.DEFAULT_VIEW_HEIGHT);
        }
        float aspect = TEXTURE_W / (float) TEXTURE_H;
        int fittedW = w;
        int fittedH = Math.round(fittedW / aspect);
        if (fittedH > h) {
            fittedH = h;
            fittedW = Math.round(fittedH * aspect);
        }
        int fittedX = x + (w - fittedW) / 2;
        int fittedY = y + (h - fittedH) / 2;
        return new MapRect(fittedX, fittedY, fittedW, fittedH);
    }

    private MapViewport mapViewport(PadMapSnapshot map, MapRect rect, double playerX, double playerZ) {
        float cell = Math.max(1.0F, map.displayScale());
        float drawW = map.width() * cell;
        float drawH = map.height() * cell;
        float centerX = rect.x() + rect.w() / 2.0F;
        float centerY = rect.y() + rect.h() / 2.0F;
        float offsetX = ((float) playerX - map.centerX()) / map.cellSizeBlocks() * cell;
        float offsetY = -((float) playerZ - map.centerZ()) / map.cellSizeBlocks() * cell;
        return new MapViewport(Math.round(centerX - drawW / 2.0F + offsetX),
                Math.round(centerY - drawH / 2.0F - offsetY), Math.round(drawW), Math.round(drawH), cell, cell);
    }

    private float screenToWorldX(float textureX, PadMapSnapshot map, MapViewport viewport) {
        return map.centerX() - (textureX - viewport.x() - viewport.w() / 2.0F)
                * map.cellSizeBlocks() / viewport.cellX();
    }

    private float screenToWorldZ(float textureY, PadMapSnapshot map, MapViewport viewport) {
        return map.centerZ() - (textureY - viewport.y() - viewport.h() / 2.0F)
                * map.cellSizeBlocks() / viewport.cellY();
    }

    private float mapScreenX(float worldX, PadMapSnapshot map, MapViewport viewport) {
        return viewport.x() + viewport.w() / 2.0F - (worldX - map.centerX())
                / map.cellSizeBlocks() * viewport.cellX();
    }

    private float mapScreenY(float worldZ, PadMapSnapshot map, MapViewport viewport) {
        return viewport.y() + viewport.h() / 2.0F - (worldZ - map.centerZ())
                / map.cellSizeBlocks() * viewport.cellY();
    }

    private String hit(TexturePoint point) {
        if (inside(point, MAP_X, MAP_Y, MAP_W, MAP_H)) {
            return "MAP";
        }
        if (inside(point, MEDIA_X, MEDIA_Y, MEDIA_W, MEDIA_H)) {
            return "MEDIA";
        }
        if (inside(point, EDITOR_X, EDITOR_Y, EDITOR_W, EDITOR_H)) {
            return "EDITOR";
        }
        if (inside(point, 300, 166, 132, 42)) {
            return "PLAYBACK";
        }
        return "NONE";
    }

    private boolean inside(TexturePoint point, int x, int y, int w, int h) {
        return point.x() >= x && point.y() >= y && point.x() < x + w && point.y() < y + h;
    }

    private TexturePoint toTexturePoint(int mouseX, int mouseY) {
        Quad quad = projectedInputQuadOrNull();
        return quad != null ? quad.toTexturePoint(mouseX, mouseY) : null;
    }

    private Quad projectedInputQuadOrNull() {
        if (!PadFocusState.hasProjectedQuad(width, height)) {
            return null;
        }
        return new Quad(
                new ScreenPoint(PadFocusState.projectedQuadX(0), PadFocusState.projectedQuadY(0)),
                new ScreenPoint(PadFocusState.projectedQuadX(1), PadFocusState.projectedQuadY(1)),
                new ScreenPoint(PadFocusState.projectedQuadX(2), PadFocusState.projectedQuadY(2)),
                new ScreenPoint(PadFocusState.projectedQuadX(3), PadFocusState.projectedQuadY(3)));
    }

    private record TexturePoint(int x, int y) {
    }

    private record MapRect(int x, int y, int w, int h) {
    }

    private record MapViewport(float x, float y, float w, float h, float cellX, float cellY) {
    }

    private record ScreenPoint(float x, float y) {
    }

    private record ProjectedSurface(int left, int top, int width, int height) {
        ProjectedSurface inflate(int amount) {
            return new ProjectedSurface(left - amount, top - amount, width + amount * 2, height + amount * 2);
        }

        boolean contains(int x, int y) {
            return x >= left && y >= top && x < left + width && y < top + height;
        }
    }

    private record Quad(ScreenPoint topLeft, ScreenPoint topRight, ScreenPoint bottomRight, ScreenPoint bottomLeft) {
        TexturePoint toTexturePoint(float screenX, float screenY) {
            if (!bounds().inflate(4).contains(Math.round(screenX), Math.round(screenY))) {
                return null;
            }
            float u = 0.5F;
            float v = 0.5F;
            for (int i = 0; i < 8; i++) {
                ScreenPoint p = sample(u, v);
                float dx = p.x() - screenX;
                float dy = p.y() - screenY;
                if (Math.abs(dx) + Math.abs(dy) < 0.01F) {
                    break;
                }
                ScreenPoint du = derivativeU(v);
                ScreenPoint dv = derivativeV(u);
                float det = du.x() * dv.y() - du.y() * dv.x();
                if (Math.abs(det) < 1.0E-4F) {
                    break;
                }
                float deltaU = (dx * dv.y() - dy * dv.x()) / det;
                float deltaV = (du.x() * dy - du.y() * dx) / det;
                u = clamp(u - deltaU);
                v = clamp(v - deltaV);
            }
            ScreenPoint resolved = sample(u, v);
            float error = Math.abs(resolved.x() - screenX) + Math.abs(resolved.y() - screenY);
            if (error > 18.0F) {
                return null;
            }
            return new TexturePoint(Math.round(u * (TEXTURE_W - 1)), Math.round(v * (TEXTURE_H - 1)));
        }

        ProjectedSurface bounds() {
            float minX = Math.min(Math.min(topLeft.x(), topRight.x()), Math.min(bottomRight.x(), bottomLeft.x()));
            float minY = Math.min(Math.min(topLeft.y(), topRight.y()), Math.min(bottomRight.y(), bottomLeft.y()));
            float maxX = Math.max(Math.max(topLeft.x(), topRight.x()), Math.max(bottomRight.x(), bottomLeft.x()));
            float maxY = Math.max(Math.max(topLeft.y(), topRight.y()), Math.max(bottomRight.y(), bottomLeft.y()));
            return new ProjectedSurface(Math.round(minX), Math.round(minY), Math.round(maxX - minX),
                    Math.round(maxY - minY));
        }

        private ScreenPoint sample(float u, float v) {
            float topX = lerp(topLeft.x(), topRight.x(), u);
            float topY = lerp(topLeft.y(), topRight.y(), u);
            float bottomX = lerp(bottomLeft.x(), bottomRight.x(), u);
            float bottomY = lerp(bottomLeft.y(), bottomRight.y(), u);
            return new ScreenPoint(lerp(topX, bottomX, v), lerp(topY, bottomY, v));
        }

        private ScreenPoint derivativeU(float v) {
            float topX = topRight.x() - topLeft.x();
            float topY = topRight.y() - topLeft.y();
            float bottomX = bottomRight.x() - bottomLeft.x();
            float bottomY = bottomRight.y() - bottomLeft.y();
            return new ScreenPoint(lerp(topX, bottomX, v), lerp(topY, bottomY, v));
        }

        private ScreenPoint derivativeV(float u) {
            float leftX = bottomLeft.x() - topLeft.x();
            float leftY = bottomLeft.y() - topLeft.y();
            float rightX = bottomRight.x() - topRight.x();
            float rightY = bottomRight.y() - topRight.y();
            return new ScreenPoint(lerp(leftX, rightX, u), lerp(leftY, rightY, u));
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private static float clamp(float value) {
            return Math.max(0.0F, Math.min(1.0F, value));
        }
    }
}