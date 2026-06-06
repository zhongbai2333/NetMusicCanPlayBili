package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 黑金风格数值滑块，带关联文本框。
 * <p>
 * 滑块和文本框双向绑定：拖动滑块更新文本框，文本框输入更新滑块。
 * 原版渲染被屏蔽，由 {@link BlackGoldScreen#drawSliderOverlays} 统一绘制。
 * </p>
 */
public class ConfigSlider extends AbstractSliderButton {
    private final float min;
    private final float max;
    private final Consumer<Float> onApply;
    EditBox linkedBox;

    public ConfigSlider(int x, int y, int w, int h, float min, float max, float cur,
            Consumer<Float> onApply) {
        super(x, y, w, h, Component.empty(), (cur - min) / (max - min));
        this.min = min;
        this.max = max;
        this.onApply = onApply;
    }

    public void setFromValue(float v) {
        value = (v - min) / (max - min);
    }

    public double getSliderValue() {
        return value;
    }

    public float getMin() {
        return min;
    }

    public float getMax() {
        return max;
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(fmt(min + (float) value * (max - min))));
    }

    @Override
    protected void applyValue() {
        float v = min + (float) value * (max - min);
        onApply.accept(v);
        if (linkedBox != null && !linkedBox.isFocused()) {
            linkedBox.setValue(fmt(v));
        }
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
    }

    public static String fmt(float v) {
        if (v == (int) v)
            return String.valueOf((int) v);
        return String.format("%.1f", v);
    }
}
