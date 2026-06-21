package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 黑金主题 GUI 基类。
 * <p>
 * 提供统一的面板、标题栏、关闭按钮和滑块 overlay 绘制。
 * 子类在 {@link #buildWidgets()} 中创建控件，在 {@link #onSave()} 中持久化。
 * </p>
 */
public abstract class BlackGoldScreen extends Screen {
    // ──── 主题常量 ────
    public static final int GOLD = BlackGoldUi.GOLD;
    public static final int GOLD_DIM = BlackGoldUi.GOLD_DIM;
    public static final int GOLD_GLOW = BlackGoldUi.GOLD_GLOW;
    public static final int BG_BLACK = BlackGoldUi.BG_BLACK;
    public static final int BG_HEADER = BlackGoldUi.BG_HEADER;
    public static final int TEXT_PRIMARY = BlackGoldUi.TEXT_PRIMARY;
    public static final int TEXT_SECONDARY = BlackGoldUi.TEXT_SECONDARY;
    public static final int TEXT_DIM = BlackGoldUi.TEXT_DIM;

    // ──── 布局常量 ────
    protected static final int BOX_W = 320;
    protected static final int BOX_H = 310;
    protected static final int HEADER_H = 28;
    protected static final int CLOSE_SIZE = 14;
    protected static final int PAD = 16;
    protected static final int SLIDER_W = 145;
    protected static final int SLIDER_H = 20;
    protected static final int VAL_W = 42;

    protected final BlockPos blockPos;
    private boolean closeHovered;

    protected BlackGoldScreen(Component title, BlockPos blockPos) {
        super(title);
        this.blockPos = blockPos.immutable();
    }

    /** 子类可覆写以调整面板高度 */
    protected int boxH() {
        return BOX_H;
    }
    // ──── 子类实现 ────

    protected abstract void buildWidgets();

    protected abstract void onSave();

    // ──── 生命周期 ────

    @Override
    protected void init() {
        buildWidgets();
    }

    @Override
    public void onClose() {
        onSave();
        if (minecraft != null)
            minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        BlackGoldUi.drawBackground(g, width, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        int bx = boxX(), by = boxY();
        List<BreakoutImpact> impacts = collectSliderBreakoutImpacts(bx, by);
        drawBox(g, bx, by);
        drawHeader(g, bx, by, mx, my);
        drawContent(g, bx, by, mx, my);
        renderWidgets(g, mx, my, pt);
        drawBreakoutBorderGaps(g, impacts);
        drawSliderOverlays(g);
    }

    /** 仅渲染子控件（绕过面板绘制），供子类在自定义布局中调用 */
    protected final void renderWidgets(GuiGraphicsExtractor g, int mx, int my, float pt) {
        super.extractRenderState(g, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean cancelled) {
        if (cancelled)
            return false;
        int bx = boxX(), by = boxY();
        int cx = bx + BOX_W - CLOSE_SIZE - 8;
        int cy = by + (HEADER_H - CLOSE_SIZE) / 2;
        if (event.x() >= cx && event.x() <= cx + CLOSE_SIZE
                && event.y() >= cy && event.y() <= cy + CLOSE_SIZE) {
            onClose();
            return true;
        }
        return super.mouseClicked(event, cancelled);
    }

    // ──── 布局 ────

    protected int boxX() {
        return (width - BOX_W) / 2;
    }

    protected int boxY() {
        return (height - boxH()) / 2;
    }

    // ──── 绘制 ────

    protected void drawBox(GuiGraphicsExtractor g, int x, int y) {
        BlackGoldUi.drawPanel(g, x, y, BOX_W, boxH());
    }

    /** 绘制标题栏和关闭按钮 */
    protected void drawHeader(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        BlackGoldUi.drawHeader(g, font, getTitle(), bx, by, BOX_W, HEADER_H);

        int cx = bx + BOX_W - CLOSE_SIZE - 8;
        int cy = by + (HEADER_H - CLOSE_SIZE) / 2;
        closeHovered = mx >= cx && mx <= cx + CLOSE_SIZE && my >= cy && my <= cy + CLOSE_SIZE;
        g.centeredText(font, Component.literal("\u2715"),
                cx + CLOSE_SIZE / 2, cy + 4, closeHovered ? GOLD : TEXT_SECONDARY);
    }

    /** 绘制面板内容区（子类覆写，默认留空） */
    protected void drawContent(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
    }

    // ──── 滑块工具 ────

    /** 便捷创建滑块 + 文本框 */
    protected ConfigSlider addConfigSlider(int x, int y, float min, float max, float cur,
            Consumer<Float> onApply) {
        ConfigSlider s = new ConfigSlider(x, y, SLIDER_W, SLIDER_H, min, max, cur, onApply);
        addRenderableWidget(s);
        EditBox box = new EditBox(font, x + SLIDER_W + 4, y, VAL_W, SLIDER_H, Component.empty());
        box.setValue(ConfigSlider.fmt(cur));
        box.setResponder(txt -> {
            try {
                float v = Float.parseFloat(txt);
                s.setFromValue(v);
                onApply.accept(v);
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(box);
        s.linkedBox = box;
        return s;
    }

    /** 便捷创建多选项按钮 */
    protected CycleWidget addCycleWidget(int x, int y, java.util.List<String> options,
            int initIndex, java.util.function.Consumer<Integer> onSwitch) {
        CycleWidget w = new CycleWidget(x, y, SLIDER_W, SLIDER_H, options, initIndex, onSwitch);
        addRenderableWidget(w);
        return w;
    }

    /** 便捷创建黑金风格的滑块重置按钮。 */
    protected void addResetButton(int x, int y, float defaultValue, ConfigSlider slider) {
        addRenderableWidget(new BlackGoldButton(x, y, 14, SLIDER_H,
                Component.literal("↺"),
                btn -> {
                    slider.setFromValue(defaultValue);
                    if (slider.linkedBox != null) {
                        slider.linkedBox.setValue(ConfigSlider.fmt(defaultValue));
                    }
                }, GOLD));
    }

    /** 绘制所有 ConfigSlider 的轨道和手柄（黑金风格） */
    protected void drawSliderOverlays(GuiGraphicsExtractor g) {
        for (var child : children()) {
            if (child instanceof ConfigSlider s) {
                drawOneSlider(g, s);
            }
        }
    }

    private void drawOneSlider(GuiGraphicsExtractor g, ConfigSlider s) {
        int x = s.getX(), y = s.getY(), w = s.getWidth(), h = s.getHeight();
        double val = s.getSliderValue();
        int pad = 4, trackY = y + h / 2 - 1, trackH = 3;
        int trackL = x + pad, trackW = w - pad * 2;
        g.fillGradient(trackL, trackY, trackL + trackW, trackY + trackH, 0xFF1A1A1A, 0xFF1A1A1A);
        int fillW = (int) (val * trackW);
        if (fillW > 0)
            g.fillGradient(trackL, trackY, trackL + fillW, trackY + trackH, GOLD_DIM, GOLD);
        int hx = trackL + fillW, hr = s.isHoveredOrFocused() ? 5 : 4;
        int hc = s.isHoveredOrFocused() ? GOLD : TEXT_PRIMARY;
        g.fillGradient(hx - hr, trackY - hr + 1, hx + hr, trackY + trackH + hr - 1, hc, hc);
        g.fillGradient(hx - 2, trackY - 1, hx + 2, trackY + trackH + 1, BG_BLACK, BG_BLACK);
    }

    private List<BreakoutImpact> collectSliderBreakoutImpacts(int bx, int by) {
        java.util.ArrayList<BreakoutImpact> impacts = new java.util.ArrayList<>();
        int h = boxH();
        int leftEdge = bx - 2;
        int rightEdge = bx + BOX_W + 2;
        for (var child : children()) {
            if (!(child instanceof ConfigSlider s)) {
                continue;
            }
            double val = s.getSliderValue();
            if (val >= 0.0D && val <= 1.0D) {
                continue;
            }

            int pad = 4;
            int trackL = s.getX() + pad;
            int trackW = s.getWidth() - pad * 2;
            int hx = trackL + (int) (val * trackW);
            int hr = s.isHoveredOrFocused() ? 5 : 4;
            boolean hitsLeftEdge = val < 0.0D && hx - hr <= leftEdge;
            boolean hitsRightEdge = val > 1.0D && hx + hr >= rightEdge;
            if (!hitsLeftEdge && !hitsRightEdge) {
                continue;
            }

            int y = s.getY() + s.getHeight() / 2;
            if (y < by || y > by + h) {
                continue;
            }

            if (hitsRightEdge) {
                impacts.add(new BreakoutImpact(false, rightEdge, y));
            } else {
                impacts.add(new BreakoutImpact(true, leftEdge, y));
            }
        }
        return impacts;
    }

    private void drawBreakoutBorderGaps(GuiGraphicsExtractor g, List<BreakoutImpact> impacts) {
        for (BreakoutImpact impact : impacts) {
            drawBreakoutBorderGap(g, impact.leftSide(), impact.edgeX(), impact.centerY());
        }
    }

    private void drawBreakoutBorderGap(GuiGraphicsExtractor g, boolean leftSide, int edgeX, int centerY) {
        int holeTop = centerY - 5;
        int holeBottom = centerY + 6;
        int gapW = 2;
        if (leftSide) {
            g.fillGradient(edgeX, holeTop, edgeX + gapW, holeBottom, BG_BLACK, BG_BLACK);
        } else {
            g.fillGradient(edgeX - gapW, holeTop, edgeX, holeBottom, BG_BLACK, BG_BLACK);
        }
    }

    private record BreakoutImpact(boolean leftSide, int edgeX, int centerY) {
    }
}
