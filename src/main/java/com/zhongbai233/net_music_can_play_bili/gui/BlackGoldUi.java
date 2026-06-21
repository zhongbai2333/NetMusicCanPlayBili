package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public final class BlackGoldUi {
    public static final int GOLD = 0xFFD4A843;
    public static final int GOLD_DIM = 0xFF6B4F12;
    public static final int GOLD_GLOW = 0x30D4A843;
    public static final int BG_BLACK = 0xFF0D0D0D;
    public static final int BG_HEADER = 0xFF1C1C1C;
    public static final int TEXT_PRIMARY = 0xFFE0D8C8;
    public static final int TEXT_SECONDARY = 0xFFA09888;
    public static final int TEXT_DIM = 0xFF605848;

    private BlackGoldUi() {
    }

    public static void drawBackground(GuiGraphicsExtractor g, int width, int height) {
        g.fillGradient(0, 0, width, height, 0xCC000000, 0xDD050505);
    }

    public static void drawPanel(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        g.fillGradient(x - 2, y - 2, x + width + 2, y + height + 2, GOLD_GLOW, GOLD_GLOW);
        g.fillGradient(x, y, x + width, y + height, BG_BLACK, BG_BLACK);
        g.fillGradient(x + 1, y + 1, x + width - 1, y + 2, 0x40FFFFFF, 0x20FFFFFF);
    }

    public static void drawHeader(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, Component title,
            int x, int y, int width, int headerHeight) {
        g.fillGradient(x + 1, y + 1, x + width - 1, y + headerHeight, BG_HEADER, BG_HEADER);
        g.fillGradient(x + 8, y + headerHeight - 1, x + width - 8, y + headerHeight, GOLD_DIM, GOLD_DIM);
        g.centeredText(font, title, x + width / 2, y + 9, GOLD);
    }

    public static void drawSlotFrame(GuiGraphicsExtractor g, int x, int y, int accentColor) {
        g.fillGradient(x - 2, y - 2, x + 20, y + 20, 0x66202020, 0x66202020);
        g.fill(x - 1, y - 1, x + 19, y + 19, accentColor);
        g.fill(x, y, x + 18, y + 18, 0xFF202020);
        g.fillGradient(x, y, x + 18, y + 1, 0x40FFFFFF, 0x20FFFFFF);
    }
}
