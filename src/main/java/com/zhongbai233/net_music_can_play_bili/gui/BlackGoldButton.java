package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 黑金风格按钮：左侧金色竖条 + 底部金色边线 + 悬停高亮。
 */
public class BlackGoldButton extends Button {
    private final int accentColor;

    public BlackGoldButton(int x, int y, int w, int h, Component msg, OnPress onPress, int accent) {
        super(x, y, w, h, msg, onPress, DEFAULT_NARRATION);
        this.accentColor = accent;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float pt) {
        var font = Minecraft.getInstance().font;
        int bg, borderC;
        if (!this.active) {
            bg = 0xFF111111;
            borderC = 0xFF222222;
        } else if (this.isHoveredOrFocused()) {
            bg = 0xFF2A2A20;
            borderC = accentColor;
        } else {
            bg = 0xFF1A1A1A;
            borderC = 0xFF333333;
        }
        g.fillGradient(getX(), getY(), getX() + width, getY() + height, bg, bg);
        g.fillGradient(getX(), getY(), getX() + 2, getY() + height, borderC, borderC);
        g.fillGradient(getX(), getY() + height - 1, getX() + width, getY() + height, borderC, borderC);

        int tc = this.active
                ? (this.isHoveredOrFocused() ? accentColor : BlackGoldScreen.TEXT_PRIMARY)
                : BlackGoldScreen.TEXT_DIM;
        g.centeredText(font, getMessage(), getX() + width / 2,
                getY() + (height - 8) / 2, tc);
    }
}
