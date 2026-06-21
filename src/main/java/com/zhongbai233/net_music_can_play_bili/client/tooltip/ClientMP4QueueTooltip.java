package com.zhongbai233.net_music_can_play_bili.client.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

public final class ClientMP4QueueTooltip implements ClientTooltipComponent {
    private static final int MAX_VISIBLE_ROWS = 8;
    private static final int PADDING_X = 7;
    private static final int PADDING_Y = 6;
    private static final int ROW_HEIGHT = 12;
    private static final int MIN_WIDTH = 118;
    private final MP4QueueTooltip tooltip;

    public ClientMP4QueueTooltip(MP4QueueTooltip tooltip) {
        this.tooltip = tooltip;
    }

    @Override
    public int getHeight(Font font) {
        return PADDING_Y * 2 + 13 + Math.min(MAX_VISIBLE_ROWS, tooltip.titles().size()) * ROW_HEIGHT;
    }

    @Override
    public int getWidth(Font font) {
        int width = font.width("MP4 播放队列");
        for (String title : tooltip.titles()) {
            width = Math.max(width, font.width(displayTitle(title)) + 18);
        }
        return Math.max(MIN_WIDTH, width + PADDING_X * 2);
    }

    @Override
    public boolean showTooltipWithItemInHand() {
        return true;
    }

    @Override
    public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor graphics) {
        int rows = Math.min(MAX_VISIBLE_ROWS, tooltip.titles().size());
        int componentHeight = getHeight(font);
        graphics.fill(x, y, x + width, y + componentHeight, 0xF0100715);
        graphics.outline(x, y, width, componentHeight, 0xFF6D4AA2);
        graphics.text(font, "MP4 播放队列", x + PADDING_X, y + PADDING_Y, 0xFFBBD8FF);
        graphics.text(font, tooltip.titles().size() + " 首",
                x + width - PADDING_X - font.width(tooltip.titles().size() + " 首"),
                y + PADDING_Y, 0xFF8B94AA);

        int selected = Math.max(0, Math.min(Math.max(0, tooltip.titles().size() - 1), tooltip.selectedIndex()));
        int first = Math.max(0, Math.min(Math.max(0, tooltip.titles().size() - rows), selected - rows / 2));
        int listY = y + PADDING_Y + 15;
        for (int row = 0; row < rows; row++) {
            int index = first + row;
            int rowY = listY + row * ROW_HEIGHT;
            boolean active = index == selected;
            if (active) {
                graphics.fill(x + 4, rowY - 1, x + width - 4, rowY + ROW_HEIGHT - 1, 0xFF263B5F);
                graphics.outline(x + 4, rowY - 1, width - 8, ROW_HEIGHT, 0xFF74C7FF);
            }
            graphics.text(font, active ? "▶" : "•", x + PADDING_X, rowY, active ? 0xFF74C7FF : 0xFF59647C);
            graphics.text(font, displayTitle(tooltip.titles().get(index)), x + PADDING_X + 13, rowY,
                    active ? 0xFFEAF2FF : 0xFFC8D0E0);
        }
        if (tooltip.titles().size() > rows) {
            int trackX = x + width - 6;
            int trackY = listY;
            int trackH = rows * ROW_HEIGHT - 2;
            int maxFirst = Math.max(1, tooltip.titles().size() - rows);
            int thumbH = Math.max(9, trackH * rows / tooltip.titles().size());
            int thumbY = trackY + (trackH - thumbH) * first / maxFirst;
            graphics.fill(trackX, trackY, trackX + 2, trackY + trackH, 0xFF2C3345);
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xFF74C7FF);
        }
    }

    private static String displayTitle(String title) {
        if (title == null || title.isBlank()) {
            return "未命名唱片";
        }
        return title.length() > 18 ? title.substring(0, 17) + "…" : title;
    }
}
