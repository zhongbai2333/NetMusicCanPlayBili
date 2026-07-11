package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSampler;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapTileKind;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class PadMapScreen extends Screen {
    private static final int PANEL_W = 360;
    private static final int PANEL_H = 240;
    private static final int HEADER_H = 24;
    private static final int MAP_PAD = 10;
    private static final int SIDEBAR_W = 96;
    private static final int CLOSE_SIZE = 14;
    private final InteractionHand hand;
    private PadMapSnapshot snapshot;
    private int panX;
    private int panZ;
    private float zoom = 1.0F;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;
    private double dragOffsetX;
    private double dragOffsetY;
    private int refreshCooldown;
    private boolean refreshRequested;
    private float lastPartialTick = 1.0F;
    private DynamicTexture mapTexture;
    private Identifier mapTextureId;
    private PadMapSnapshot renderedSnapshot;

    public PadMapScreen(InteractionHand hand) {
        super(Component.translatable("gui.net_music_can_play_bili.pad.map"));
        this.hand = hand;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        centerOnPlayer();
        PadMapClientCache.setManualView(panX, panZ, zoom);
        refreshRequested = true;
    }

    @Override
    public void tick() {
        if (dragging) {
            return;
        }
        if (refreshCooldown > 0) {
            refreshCooldown--;
        }
        if (refreshRequested && refreshCooldown <= 0) {
            refreshRequested = false;
            refreshMap(true);
        } else if (refreshCooldown <= 0) {
            refreshMap(false);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, 0xE0101720, 0xF00B1018);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        lastPartialTick = partialTick;
        int x = panelX();
        int y = panelY();
        drawPanel(graphics, x, y, mouseX, mouseY);
        drawMap(graphics, mapX(), mapY(), mapW(), mapH());
        drawSidebar(graphics, x + PANEL_W - SIDEBAR_W, y + HEADER_H, SIDEBAR_W, PANEL_H - HEADER_H);
    }

    @Override
    public void onClose() {
        PadMapClientCache.clearManualView();
        if (mapTexture != null) {
            mapTexture.close();
            mapTexture = null;
        }
        super.onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean cancelled) {
        if (cancelled) {
            return false;
        }
        int x = panelX();
        int y = panelY();
        int closeX = x + PANEL_W - CLOSE_SIZE - 7;
        int closeY = y + (HEADER_H - CLOSE_SIZE) / 2;
        if (event.x() >= closeX && event.x() <= closeX + CLOSE_SIZE && event.y() >= closeY
                && event.y() <= closeY + CLOSE_SIZE) {
            onClose();
            return true;
        }
        if (event.button() == 0 && inMap(event.x(), event.y())) {
            dragging = true;
            lastMouseX = event.x();
            lastMouseY = event.y();
            return true;
        }
        return super.mouseClicked(event, cancelled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging && event.button() == 0) {
            double dx = event.x() - lastMouseX;
            double dy = event.y() - lastMouseY;
            dragOffsetX += dx;
            dragOffsetY += dy;
            lastMouseX = event.x();
            lastMouseY = event.y();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging && event.button() == 0) {
            commitDragOffset();
            dragging = false;
            refreshRequested = true;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inMap(mouseX, mouseY)) {
            commitDragOffset();
            zoom = Math.max(0.35F, Math.min(4.0F, zoom + (float) scrollY * 0.18F));
            PadMapClientCache.setManualView(panX, panZ, zoom);
            refreshRequested = true;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void centerOnPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        panX = minecraft.player.blockPosition().getX();
        panZ = minecraft.player.blockPosition().getZ();
    }

    private void refreshMap(boolean immediate) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || (!immediate && refreshCooldown > 0)) {
            return;
        }
        snapshot = PadMapClientCache.snapshot(panX, panZ);
        updateMapTexture(snapshot);
        refreshCooldown = immediate ? 4 : 20;
    }

    private void updateMapTexture(PadMapSnapshot current) {
        if (current == null || current == renderedSnapshot) {
            return;
        }
        ensureMapTexture(current.width(), current.height());
        if (mapTexture == null || mapTexture.getPixels() == null || mapTexture.getPixels().isClosed()) {
            return;
        }
        NativeImage image = mapTexture.getPixels();
        int width = current.width();
        int height = current.height();
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                PadMapTileKind tile = current.tile(x, z);
                image.setPixel(x, z, tile == null ? PadMapTileKind.UNKNOWN.color() : tile.color());
            }
        }
        mapTexture.upload();
        renderedSnapshot = current;
    }

    private void ensureMapTexture(int width, int height) {
        boolean sizeMatches = mapTexture != null && mapTexture.getPixels() != null
                && !mapTexture.getPixels().isClosed()
                && mapTexture.getPixels().getWidth() == width
                && mapTexture.getPixels().getHeight() == height;
        if (sizeMatches) {
            return;
        }
        if (mapTexture != null) {
            mapTexture.close();
        }
        mapTextureId = Identifier.fromNamespaceAndPath("net_music_can_play_bili",
                "dynamic/pad_map_screen_" + hand.name().toLowerCase(java.util.Locale.ROOT));
        mapTexture = new DynamicTexture(mapTextureId.toString(), width, height, false);
        Minecraft.getInstance().getTextureManager().register(mapTextureId, mapTexture);
    }

    private void drawPanel(GuiGraphicsExtractor g, int x, int y, int mouseX, int mouseY) {
        g.fillGradient(x - 2, y - 2, x + PANEL_W + 2, y + PANEL_H + 2, 0x5537A9FF, 0x5537A9FF);
        g.fillGradient(x, y, x + PANEL_W, y + PANEL_H, 0xFF101721, 0xFF0C1119);
        g.fillGradient(x + 1, y + 1, x + PANEL_W - 1, y + HEADER_H, 0xFF172232, 0xFF172232);
        g.centeredText(font, getTitle(), x + PANEL_W / 2, y + 8, 0xFFBDE7FF);
        int closeX = x + PANEL_W - CLOSE_SIZE - 7;
        int closeY = y + (HEADER_H - CLOSE_SIZE) / 2;
        boolean hovered = mouseX >= closeX && mouseX <= closeX + CLOSE_SIZE && mouseY >= closeY
                && mouseY <= closeY + CLOSE_SIZE;
        g.centeredText(font, Component.literal("✕"), closeX + CLOSE_SIZE / 2, closeY + 4,
                hovered ? 0xFFFFFFFF : 0xFF8EA0B5);
    }

    private void drawMap(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fillGradient(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF263241, 0xFF263241);
        g.fillGradient(x, y, x + w, y + h, 0xFF1D2834, 0xFF1D2834);
        if (snapshot == null) {
            g.centeredText(font, Component.literal("Loading map..."), x + w / 2, y + h / 2 - 4, 0xFFB9C7D7);
            return;
        }
        int drawW = snapshot.width();
        int drawH = snapshot.height();
        float cellPx = Math.max(1.0F, Math.min((float) w / drawW, (float) h / drawH));
        drawW = Math.round(drawW * cellPx);
        drawH = Math.round(drawH * cellPx);
        int originX = x + (w - drawW) / 2;
        int originY = y + (h - drawH) / 2;
        int visualOriginX = originX + (int) Math.round(dragOffsetX);
        int visualOriginY = originY + (int) Math.round(dragOffsetY);
        if (mapTextureId != null) {
            g.blit(mapTextureId, visualOriginX, visualOriginY, visualOriginX + drawW, visualOriginY + drawH, 0.0F,
                    1.0F, 1.0F, 0.0F);
        }
        drawGrid(g, visualOriginX, visualOriginY, drawW, drawH, cellPx);
        drawPlayerMarker(g, visualOriginX, visualOriginY, cellPx);
        drawTriggerPins(g, visualOriginX, visualOriginY, cellPx);
    }

    private void commitDragOffset() {
        if (snapshot == null || (Math.abs(dragOffsetX) < 0.5D && Math.abs(dragOffsetY) < 0.5D)) {
            dragOffsetX = 0.0D;
            dragOffsetY = 0.0D;
            return;
        }
        float pixelsPerBlock = pixelsPerBlock(snapshot.cellSizeBlocks());
        panX -= Math.round(dragOffsetX / Math.max(0.5F, pixelsPerBlock));
        panZ -= Math.round(dragOffsetY / Math.max(0.5F, pixelsPerBlock));
        dragOffsetX = 0.0D;
        dragOffsetY = 0.0D;
        PadMapClientCache.setManualView(panX, panZ, zoom);
    }

    private void drawGrid(GuiGraphicsExtractor g, int originX, int originY, int drawW, int drawH, float cellPx) {
        if (cellPx < 3.0F) {
            return;
        }
        int lineColor = 0x204D5C6B;
        for (int i = 0; i <= PadMapSampler.DEFAULT_WIDTH; i += 8) {
            int p = originX + Math.round(i * cellPx);
            g.fillGradient(p, originY, p + 1, originY + drawH, lineColor, lineColor);
        }
        for (int i = 0; i <= PadMapSampler.DEFAULT_HEIGHT; i += 8) {
            int q = originY + Math.round(i * cellPx);
            g.fillGradient(originX, q, originX + drawW, q + 1, lineColor, lineColor);
        }
    }

    private void drawPlayerMarker(GuiGraphicsExtractor g, int originX, int originY, float cellPx) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || snapshot == null) {
            return;
        }
        double playerX = minecraft.player.xo + (minecraft.player.getX() - minecraft.player.xo) * lastPartialTick;
        double playerZ = minecraft.player.zo + (minecraft.player.getZ() - minecraft.player.zo) * lastPartialTick;
        int px = worldToMapX(playerX, originX, cellPx);
        int pz = worldToMapZ(playerZ, originY, cellPx);
        drawDiamond(g, px, pz, 5, 0xFFFFFFFF, 0xFF1E88FF);
    }

    private void drawTriggerPins(GuiGraphicsExtractor g, int originX, int originY, float cellPx) {
        PadDocument document = document();
        int index = 1;
        for (PadTriggerPoint point : document.triggerPoints()) {
            int px = worldToMapX(point.x(), originX, cellPx);
            int pz = worldToMapZ(point.z(), originY, cellPx);
            if (px < mapX() || pz < mapY() || px > mapX() + mapW() || pz > mapY() + mapH()) {
                continue;
            }
            drawPin(g, px, pz, point.mediaId() > 0 ? 0xFFFF5A66 : 0xFFFFC857);
            g.centeredText(font, Component.literal(String.valueOf(index++)), px, pz - 18, 0xFFFFFFFF);
        }
    }

    private void drawSidebar(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        PadDocument document = document();
        g.fillGradient(x, y, x + w, y + h, 0xE6151D28, 0xE6151D28);
        g.text(font, Component.literal("地图"), x + 8, y + 10, 0xFFBDE7FF);
        g.text(font, Component.literal("缩放 x" + String.format(java.util.Locale.ROOT, "%.1f", zoom)), x + 8,
                y + 26, 0xFF8EA0B5);
        g.text(font, Component.literal("媒体 " + document.mediaEntries().size()), x + 8, y + 48, 0xFFE8E0C8);
        g.text(font, Component.literal("点位 " + document.triggerPoints().size()), x + 8, y + 62, 0xFFFFC857);
        g.text(font, Component.literal(document.locked() ? "已锁定" : "草稿"), x + 8, y + 84,
                document.locked() ? 0xFFFFC857 : 0xFF6FE28A);
        g.text(font, Component.literal("拖拽平移"), x + 8, y + h - 42, 0xFF7E8EA3);
        g.text(font, Component.literal("滚轮缩放"), x + 8, y + h - 28, 0xFF7E8EA3);
        g.text(font, Component.literal("点位播放→Pad"), x + 8, y + h - 14, 0xFF6FD2FF);
    }

    private void drawDiamond(GuiGraphicsExtractor g, int x, int y, int radius, int border, int fill) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = radius - Math.abs(dy);
            g.fillGradient(x - half - 1, y + dy, x + half + 2, y + dy + 1, border, border);
        }
        for (int dy = -radius + 2; dy <= radius - 2; dy++) {
            int half = radius - 2 - Math.abs(dy);
            g.fillGradient(x - half, y + dy, x + half + 1, y + dy + 1, fill, fill);
        }
    }

    private void drawPin(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fillGradient(x - 4, y - 12, x + 5, y - 3, color, color);
        g.fillGradient(x - 2, y - 3, x + 3, y + 4, color, color);
        g.fillGradient(x - 1, y + 4, x + 2, y + 7, color, color);
        g.fillGradient(x - 2, y - 10, x + 3, y - 5, 0xFFFFFFFF, 0xFFFFFFFF);
    }

    private int worldToMapX(double worldX, int originX, float cellPx) {
        return originX + Math.round((float) ((worldX - snapshot.centerX()) / snapshot.cellSizeBlocks() * cellPx)
                + snapshot.width() * cellPx / 2.0F);
    }

    private int worldToMapZ(double worldZ, int originY, float cellPx) {
        return originY + Math.round((float) ((worldZ - snapshot.centerZ()) / snapshot.cellSizeBlocks() * cellPx)
                + snapshot.height() * cellPx / 2.0F);
    }

    private float pixelsPerBlock(int cellSize) {
        int mapWidth = snapshot != null ? snapshot.width() : PadMapSampler.DEFAULT_WIDTH;
        int mapHeight = snapshot != null ? snapshot.height() : PadMapSampler.DEFAULT_HEIGHT;
        return Math.min((float) mapW() / Math.max(1, mapWidth), (float) mapH() / Math.max(1, mapHeight))
                / Math.max(1, cellSize);
    }

    private boolean inMap(double mouseX, double mouseY) {
        return mouseX >= mapX() && mouseX <= mapX() + mapW() && mouseY >= mapY() && mouseY <= mapY() + mapH();
    }

    private int panelX() {
        return (width - PANEL_W) / 2;
    }

    private int panelY() {
        return (height - PANEL_H) / 2;
    }

    private int mapX() {
        return panelX() + MAP_PAD;
    }

    private int mapY() {
        return panelY() + HEADER_H + MAP_PAD;
    }

    private int mapW() {
        return PANEL_W - SIDEBAR_W - MAP_PAD * 2;
    }

    private int mapH() {
        return PANEL_H - HEADER_H - MAP_PAD * 2;
    }

    private PadDocument document() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return PadDocument.DEFAULT;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof PadItem)) {
            return PadDocument.DEFAULT;
        }
        return PadItem.readDocument(stack);
    }
}