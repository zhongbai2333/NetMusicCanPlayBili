package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 黑金风格多选项循环切换按钮。
 * <p>
 * 点击在选项列表中循环，选中项通过回调通知。
 * 原版渲染被屏蔽，由主题方法统一绘制。
 * </p>
 */
public class CycleWidget extends AbstractWidget {
    private final List<String> options;
    private int index;
    private final Consumer<Integer> onSwitch;

    public CycleWidget(int x, int y, int w, int h, List<String> options, int initIndex,
            Consumer<Integer> onSwitch) {
        super(x, y, w, h, Component.empty());
        this.options = options;
        this.index = Math.clamp(initIndex, 0, options.size() - 1);
        this.onSwitch = onSwitch;
    }

    public String currentOption() {
        return options.get(index);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        // 由主题方法统一绘制
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean cancelled) {
        if (cancelled)
            return false;
        if (isMouseOver(event.x(), event.y())) {
            index = (index + 1) % options.size();
            onSwitch.accept(index);
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput o) {
    }
}
