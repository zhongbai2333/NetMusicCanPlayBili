package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.menu.MediaToolReportMenu;
import com.zhongbai233.net_music_can_play_bili.menu.MediaToolReportMenu.ReportSourceInfo;
import com.zhongbai233.net_music_can_play_bili.network.MediaToolReportPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.List;

public class MediaToolReportScreen extends AbstractContainerScreen<MediaToolReportMenu> {
    private static final int WIDTH = 260;
    private static final int HEIGHT = 220;
    private static final int LIST_TOP = 54;
    private static final int ROW_HEIGHT = 24;
    private int selectedIndex;

    public MediaToolReportScreen(MediaToolReportMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.selectedIndex = menu.sources().size() == 1 ? 0 : -1;
    }

    @Override
    protected void init() {
        super.init();
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();
        List<ReportSourceInfo> sources = menu.sources();
        int rows = Math.min(MediaToolReportMenu.MAX_SOURCES, sources.size());
        for (int i = 0; i < rows; i++) {
            int index = i;
            ReportSourceInfo source = sources.get(i);
            addRenderableWidget(new BlackGoldButton(leftPos + 14, topPos + LIST_TOP + i * ROW_HEIGHT,
                    imageWidth - 28, 20, rowButtonText(source, i), button -> {
                        selectedIndex = index;
                        rebuildButtons();
                    }, i == selectedIndex ? BlackGoldUi.GOLD : 0xFF555555));
        }
        BlackGoldButton confirm = new BlackGoldButton(leftPos + imageWidth - 104, topPos + imageHeight - 30,
                90, 20, Component.translatable("gui.net_music_can_play_bili.media_tool_report.confirm"),
                button -> confirmSelected(), BlackGoldUi.GOLD);
        confirm.active = selectedIndex >= 0 && selectedIndex < sources.size();
        addRenderableWidget(confirm);
        addRenderableWidget(new BlackGoldButton(leftPos + 14, topPos + imageHeight - 30,
                70, 20, Component.translatable("gui.cancel"), button -> onClose(), 0xFF777777));
    }

    private Component rowButtonText(ReportSourceInfo source, int index) {
        String prefix = index == selectedIndex ? "▶ " : "  ";
        return Component.literal(prefix + source.kindShortName() + " · " + source.shortSongName()
                + " · " + source.distanceText());
    }

    private void confirmSelected() {
        List<ReportSourceInfo> sources = menu.sources();
        if (selectedIndex < 0 || selectedIndex >= sources.size()) {
            return;
        }
        ClientPacketDistributor.sendToServer(new MediaToolReportPacket(sources.get(selectedIndex).key()));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        BlackGoldUi.drawBackground(guiGraphics, width, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        int lx = leftPos, ty = topPos;
        BlackGoldUi.drawPanel(g, lx, ty, imageWidth, imageHeight);
        BlackGoldUi.drawHeader(g, font, title, lx, ty, imageWidth, 26);
        g.text(font, Component.translatable("gui.net_music_can_play_bili.media_tool_report.subtitle"),
                lx + 14, ty + 34, BlackGoldUi.TEXT_SECONDARY, false);

        if (selectedIndex >= 0 && selectedIndex < menu.sources().size()) {
            drawDetails(g, menu.sources().get(selectedIndex), lx, ty);
        } else {
            g.text(font, Component.translatable("gui.net_music_can_play_bili.media_tool_report.select_hint")
                    .withStyle(ChatFormatting.GRAY), lx + 14, ty + imageHeight - 54, BlackGoldUi.TEXT_DIM, false);
        }

        super.extractRenderState(g, mx, my, pt);
    }

    private void drawDetails(GuiGraphicsExtractor g, ReportSourceInfo source, int lx, int ty) {
        int y = ty + imageHeight - 72;
        g.text(font, Component.literal(source.kindDisplayName() + " · " + source.shortSongName()),
                lx + 14, y, BlackGoldUi.GOLD, false);
        g.text(font, Component.translatable("gui.net_music_can_play_bili.media_tool_report.detail_line",
                source.shortOwnerName(), source.progressText(), source.positionText()),
                lx + 14, y + 11, BlackGoldUi.TEXT_SECONDARY, false);
        if (!source.rawUrl().isBlank()) {
            g.text(font, Component.literal(trim(source.rawUrl(), 48)),
                    lx + 14, y + 22, BlackGoldUi.TEXT_DIM, false);
        }
    }

    private static String trim(String value, int maxLength) {
        String safe = value != null ? value : "";
        return safe.length() <= maxLength ? safe : safe.substring(0, Math.max(1, maxLength - 1)) + "…";
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {
    }
}