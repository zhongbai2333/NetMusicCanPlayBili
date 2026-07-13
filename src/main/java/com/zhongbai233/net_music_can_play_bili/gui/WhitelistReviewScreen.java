package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewActionPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.List;

/** OP4 使用的游戏内白名单审核界面。 */
public class WhitelistReviewScreen extends Screen {
    private static WhitelistReviewPacket lastPayload;
    private static final int BOX_W = 420;
    private static final int BOX_H = 286;
    private static final int HEADER_H = 28;
    private static final int CLOSE_SIZE = 14;
    private static final int ROW_TOP = 48;
    private static final int ROW_HEIGHT = 22;
    private static final int VISIBLE_ROWS = 7;
    private static final int SCROLLBAR_WIDTH = 5;
    private static final int SCROLLBAR_MIN_THUMB_HEIGHT = 18;
    private static final int DETAIL_TOP = ROW_TOP + VISIBLE_ROWS * ROW_HEIGHT + 10;

    private List<WhitelistReviewPacket.Entry> entries;
    private int selectedIndex;
    private int scrollOffset;
    private boolean closeHovered;
    private boolean draggingScrollbar;
    private double scrollbarDragOffsetY;

    public WhitelistReviewScreen(WhitelistReviewPacket payload) {
        super(Component.literal("白名单统一审核"));
        lastPayload = payload;
        this.entries = safeEntries(payload);
        this.selectedIndex = entries.isEmpty() ? -1 : 0;
    }

    public static WhitelistReviewPacket lastPayload() {
        return lastPayload;
    }

    public static void open(WhitelistReviewPacket payload) {
        Minecraft.getInstance().setScreen(new WhitelistReviewScreen(payload));
    }

    public static void openOrUpdate(WhitelistReviewPacket payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof WhitelistReviewScreen screen) {
            screen.update(payload);
        } else {
            minecraft.setScreen(new WhitelistReviewScreen(payload));
        }
    }

    public void update(WhitelistReviewPacket payload) {
        String selectedId = selectedEntry() != null ? selectedEntry().id() : "";
        lastPayload = payload;
        this.entries = safeEntries(payload);
        this.selectedIndex = indexOf(selectedId);
        if (selectedIndex < 0 && !entries.isEmpty()) {
            selectedIndex = Math.min(scrollOffset, entries.size() - 1);
        }
        clampScrollOffset();
        ensureSelectedVisible();
        rebuildButtons();
    }

    @Override
    protected void init() {
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();
        int bx = boxX();
        int by = boxY();
        addRenderableWidget(new BlackGoldButton(bx + 14, by + BOX_H - 28, 58, 20,
                Component.literal("刷新"), button -> request(WhitelistReviewActionPacket.Action.REFRESH, ""),
                BlackGoldUi.GOLD));
        addRenderableWidget(new BlackGoldButton(bx + 78, by + BOX_H - 28, 58, 20,
                Component.literal("导出"), button -> request(WhitelistReviewActionPacket.Action.EXPORT, ""),
                BlackGoldUi.GOLD));
        BlackGoldButton preview = new BlackGoldButton(bx + 142, by + BOX_H - 28, 58, 20,
                Component.literal("查看"), button -> previewSelected(), BlackGoldUi.GOLD);
        preview.active = selectedEntry() != null;
        addRenderableWidget(preview);
        BlackGoldButton remove = new BlackGoldButton(bx + BOX_W - 88, by + BOX_H - 28, 74, 20,
                Component.literal("移除选中"), button -> removeSelected(), 0xFFFF6B6B);
        remove.active = selectedEntry() != null;
        addRenderableWidget(remove);
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
        int bx = boxX();
        int by = boxY();
        BlackGoldUi.drawPanel(g, bx, by, BOX_W, BOX_H);
        drawHeader(g, bx, by, mx, my);
        drawList(g, bx, by, mx, my);
        drawDetails(g, bx, by);
        super.extractRenderState(g, mx, my, pt);
    }

    private void drawHeader(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        BlackGoldUi.drawHeader(g, font, getTitle(), bx, by, BOX_W, HEADER_H);
        g.text(font, Component.literal("白名单审核 · " + entries.size() + " 条 · 选择条目可查看或移除"),
                bx + 14, by + 34, BlackGoldUi.TEXT_SECONDARY, false);
        int cx = bx + BOX_W - CLOSE_SIZE - 8;
        int cy = by + (HEADER_H - CLOSE_SIZE) / 2;
        closeHovered = mx >= cx && mx <= cx + CLOSE_SIZE && my >= cy && my <= cy + CLOSE_SIZE;
        g.centeredText(font, Component.literal("✕"), cx + CLOSE_SIZE / 2, cy + 4,
                closeHovered ? BlackGoldUi.GOLD : BlackGoldUi.TEXT_SECONDARY);
    }

    private void drawList(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        if (entries.isEmpty()) {
            g.centeredText(font, Component.literal("当前白名单为空"), bx + BOX_W / 2, by + 116,
                    BlackGoldUi.TEXT_DIM);
            return;
        }
        int rows = Math.min(VISIBLE_ROWS, entries.size() - scrollOffset);
        for (int i = 0; i < rows; i++) {
            int index = scrollOffset + i;
            WhitelistReviewPacket.Entry entry = entries.get(index);
            int x = bx + 14;
            int y = by + ROW_TOP + i * ROW_HEIGHT;
            boolean selected = index == selectedIndex;
            boolean hovered = mx >= x && mx <= x + BOX_W - 28 && my >= y && my <= y + ROW_HEIGHT - 2;
            int bg = selected ? 0xFF263B5F : hovered ? 0xFF202018 : 0xFF151515;
            g.fillGradient(x, y, x + BOX_W - 28, y + ROW_HEIGHT - 2, bg, bg);
            g.fillGradient(x, y, x + 2, y + ROW_HEIGHT - 2,
                    selected ? BlackGoldUi.GOLD : 0xFF333333, selected ? BlackGoldUi.GOLD : 0xFF333333);
            String prefix = selected ? "▶ " : "  ";
            g.text(font, Component.literal(prefix + "[" + entry.type() + "] " + trim(entry.id(), 42)),
                    x + 6, y + 6, selected ? BlackGoldUi.GOLD : BlackGoldUi.TEXT_PRIMARY, false);
            g.text(font, Component.literal(trim(entry.addedByName(), 14)),
                    bx + BOX_W - 116, y + 6, BlackGoldUi.TEXT_SECONDARY, false);
        }
        if (entries.size() > VISIBLE_ROWS) {
            drawScrollbar(g, bx, by, mx, my);
            g.text(font, Component.literal((scrollOffset + 1) + "-" + (scrollOffset + rows) + "/" + entries.size()),
                    bx + BOX_W - 76, by + 34, BlackGoldUi.TEXT_DIM, false);
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        int trackX = scrollbarX(bx);
        int trackY = scrollbarY(by);
        int trackHeight = scrollbarHeight();
        int thumbY = scrollbarThumbY(by);
        int thumbHeight = scrollbarThumbHeight();
        boolean hovered = mx >= trackX && mx <= trackX + SCROLLBAR_WIDTH
                && my >= thumbY && my <= thumbY + thumbHeight;
        g.fillGradient(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackHeight,
                0xFF242424, 0xFF242424);
        int thumbColor = draggingScrollbar || hovered ? BlackGoldUi.GOLD : BlackGoldUi.GOLD_DIM;
        g.fillGradient(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight,
                thumbColor, thumbColor);
    }

    private void drawDetails(GuiGraphicsExtractor g, int bx, int by) {
        WhitelistReviewPacket.Entry entry = selectedEntry();
        int x = bx + 14;
        int y = by + DETAIL_TOP;
        g.fillGradient(x, y, bx + BOX_W - 14, by + BOX_H - 36, 0xFF111111, 0xFF111111);
        if (entry == null) {
            g.text(font, Component.literal("选择一个条目查看详情"), x + 8, y + 10, BlackGoldUi.TEXT_DIM, false);
            return;
        }
        g.text(font, Component.literal("资源：" + trim(entry.id(), 58)), x + 8, y + 8, BlackGoldUi.GOLD, false);
        g.text(font,
                Component.literal("添加者：" + emptyAs(entry.addedByName(), "未知") + "  时间：" + trim(entry.addedAt(), 32)),
                x + 8, y + 21, BlackGoldUi.TEXT_SECONDARY, false);
        g.text(font, Component.literal("原始输入：" + trim(emptyAs(entry.originalInput(), entry.id()), 62)),
                x + 8, y + 34, BlackGoldUi.TEXT_DIM, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean cancelled) {
        if (cancelled) {
            return false;
        }
        int bx = boxX();
        int by = boxY();
        int cx = bx + BOX_W - CLOSE_SIZE - 8;
        int cy = by + (HEADER_H - CLOSE_SIZE) / 2;
        if (event.x() >= cx && event.x() <= cx + CLOSE_SIZE && event.y() >= cy && event.y() <= cy + CLOSE_SIZE) {
            onClose();
            return true;
        }
        if (event.button() == 0 && entries.size() > VISIBLE_ROWS && inScrollbar(event.x(), event.y(), bx, by)) {
            int thumbY = scrollbarThumbY(by);
            int thumbHeight = scrollbarThumbHeight();
            if (event.y() < thumbY || event.y() > thumbY + thumbHeight) {
                scrollbarDragOffsetY = thumbHeight / 2.0D;
                scrollToScrollbarPosition(event.y(), by);
            } else {
                scrollbarDragOffsetY = event.y() - thumbY;
            }
            draggingScrollbar = true;
            return true;
        }
        int listX = bx + 14;
        int listY = by + ROW_TOP;
        if (event.x() >= listX && event.x() <= listX + BOX_W - 28
                && event.y() >= listY && event.y() < listY + VISIBLE_ROWS * ROW_HEIGHT) {
            int row = ((int) event.y() - listY) / ROW_HEIGHT;
            int index = scrollOffset + row;
            if (index >= 0 && index < entries.size()) {
                selectedIndex = index;
                rebuildButtons();
                return true;
            }
        }
        return super.mouseClicked(event, cancelled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScrollbar && event.button() == 0) {
            scrollToScrollbarPosition(event.y(), boxY());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScrollbar && event.button() == 0) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (entries.size() <= VISIBLE_ROWS || scrollY == 0.0D || !inList(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int previousOffset = scrollOffset;
        scrollOffset += scrollY < 0.0D ? 1 : -1;
        clampScrollOffset();
        return scrollOffset != previousOffset || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void removeSelected() {
        WhitelistReviewPacket.Entry entry = selectedEntry();
        if (entry != null) {
            request(WhitelistReviewActionPacket.Action.REMOVE, entry.id());
        }
    }

    private void previewSelected() {
        WhitelistReviewPacket.Entry entry = selectedEntry();
        if (entry != null) {
            request(WhitelistReviewActionPacket.Action.PREVIEW, entry.id());
        }
    }

    private void request(WhitelistReviewActionPacket.Action action, String value) {
        ClientPacketDistributor.sendToServer(new WhitelistReviewActionPacket(action, value));
    }

    private WhitelistReviewPacket.Entry selectedEntry() {
        return selectedIndex >= 0 && selectedIndex < entries.size() ? entries.get(selectedIndex) : null;
    }

    private int indexOf(String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (id.equals(entries.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    private void clampScrollOffset() {
        int max = Math.max(0, entries.size() - VISIBLE_ROWS);
        scrollOffset = Math.max(0, Math.min(max, scrollOffset));
    }

    private void ensureSelectedVisible() {
        if (selectedIndex >= 0) {
            if (selectedIndex < scrollOffset) {
                scrollOffset = selectedIndex;
            } else if (selectedIndex >= scrollOffset + VISIBLE_ROWS) {
                scrollOffset = Math.max(0, selectedIndex - VISIBLE_ROWS + 1);
            }
        }
    }

    private void scrollToScrollbarPosition(double mouseY, int by) {
        int maxScroll = Math.max(0, entries.size() - VISIBLE_ROWS);
        int travel = scrollbarHeight() - scrollbarThumbHeight();
        if (maxScroll <= 0 || travel <= 0) {
            scrollOffset = 0;
            return;
        }
        double thumbTop = mouseY - scrollbarDragOffsetY;
        double progress = (thumbTop - scrollbarY(by)) / travel;
        scrollOffset = (int) Math.round(progress * maxScroll);
        clampScrollOffset();
    }

    private boolean inList(double mouseX, double mouseY) {
        int bx = boxX();
        int by = boxY();
        return mouseX >= bx + 14 && mouseX <= bx + BOX_W - 14
                && mouseY >= by + ROW_TOP && mouseY < by + ROW_TOP + VISIBLE_ROWS * ROW_HEIGHT;
    }

    private boolean inScrollbar(double mouseX, double mouseY, int bx, int by) {
        int x = scrollbarX(bx);
        int y = scrollbarY(by);
        return mouseX >= x && mouseX <= x + SCROLLBAR_WIDTH
                && mouseY >= y && mouseY <= y + scrollbarHeight();
    }

    private int scrollbarX(int bx) {
        return bx + BOX_W - 20;
    }

    private int scrollbarY(int by) {
        return by + ROW_TOP;
    }

    private int scrollbarHeight() {
        return VISIBLE_ROWS * ROW_HEIGHT - 2;
    }

    private int scrollbarThumbHeight() {
        return Math.max(SCROLLBAR_MIN_THUMB_HEIGHT,
                scrollbarHeight() * VISIBLE_ROWS / Math.max(VISIBLE_ROWS, entries.size()));
    }

    private int scrollbarThumbY(int by) {
        int maxScroll = Math.max(0, entries.size() - VISIBLE_ROWS);
        int travel = scrollbarHeight() - scrollbarThumbHeight();
        return scrollbarY(by) + (maxScroll == 0 ? 0 : Math.round((float) travel * scrollOffset / maxScroll));
    }

    private int boxX() {
        return (width - BOX_W) / 2;
    }

    private int boxY() {
        return (height - BOX_H) / 2;
    }

    private static List<WhitelistReviewPacket.Entry> safeEntries(WhitelistReviewPacket payload) {
        return payload == null || payload.entries() == null ? List.of() : payload.entries();
    }

    private static String trim(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(0, Math.max(1, maxLength - 1)) + "…";
    }

    private static String emptyAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
