package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.gui.core.WhitelistReviewSelection;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewActionPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewMutationResultPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.List;
import java.util.Objects;

/** OP4 使用的游戏内白名单审核界面。 */
public class WhitelistReviewScreen extends Screen {
    private static WhitelistReviewPacket lastPayload;
    private static WhitelistReviewScreen suspendedForPreview;
    private static final int PREFERRED_BOX_W = 420;
    private static final int PREFERRED_BOX_H = 360;
    private static final int MIN_BOX_W = 300;
    private static final int MIN_BOX_H = 220;
    private static final int HEADER_H = 28;
    private static final int CLOSE_SIZE = 14;
    private static final int ROW_TOP = 70;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_VISIBLE_ROWS = 7;
    private static final int MIN_DETAIL_HEIGHT = 60;
    private static final int SCROLLBAR_WIDTH = 5;
    private static final int SCROLLBAR_MIN_THUMB_HEIGHT = 18;
    private static final long REQUEST_TIMEOUT_MILLIS = 10_000L;

    private List<WhitelistReviewPacket.Entry> entries;
    private List<WhitelistReviewPacket.RemovalRecord> removalRecords;
    private int selectedIndex;
    private String selectedRemovalId = "";
    private int scrollOffset;
    private boolean closeHovered;
    private boolean draggingScrollbar;
    private double scrollbarDragOffsetY;
    private boolean historyTab;
    private PromptMode promptMode = PromptMode.NONE;
    private String promptTargetId = "";
    private String promptExpectedAddedAt = "";
    private String promptDraft = "";
    private String preparedRemovalTargetId = "";
    private String preferredSelectionAfterRemoval = "";
    private String interactionStatus = "";
    private PromptMode pendingMode = PromptMode.NONE;
    private String pendingTargetId = "";
    private long pendingRequestId;
    private String pendingPreviewTargetId = "";
    private long pendingPreviewRequestId;
    private long previewRequestStartedAt;
    private boolean pageRequestPending;
    private long pageRequestId;
    private int pageRequestDirection;
    private long requestStartedAt;
    private EditBox promptField;
    private BlackGoldButton promptSubmitButton;

    private enum PromptMode { NONE, REMOVE, COMMENT }

    public WhitelistReviewScreen(WhitelistReviewPacket payload) {
        this(payload, "");
    }

    public WhitelistReviewScreen(WhitelistReviewPacket payload, String selectedId) {
        super(Component.literal("白名单统一审核"));
        lastPayload = payload;
        this.entries = safeEntries(payload);
        this.removalRecords = safeRemovalRecords(payload);
        this.selectedIndex = indexOf(selectedId);
        if (selectedIndex < 0 && !entries.isEmpty()) {
            selectedIndex = 0;
        }
        ensureSelectedVisible();
    }

    public static WhitelistReviewPacket lastPayload() {
        return lastPayload;
    }

    public static void open(WhitelistReviewPacket payload) {
        suspendedForPreview = null;
        Minecraft.getInstance().setScreen(new WhitelistReviewScreen(payload));
    }

    public static void openOrUpdate(WhitelistReviewPacket payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof WhitelistReviewScreen screen) {
            suspendedForPreview = null;
            screen.update(payload);
        } else if (payload != null && payload.openScreen()) {
            WhitelistReviewScreen target = suspendedForPreview;
            suspendedForPreview = null;
            if (target == null) {
                target = new WhitelistReviewScreen(payload);
            } else {
                target.update(payload);
            }
            minecraft.setScreen(target);
        } else if (minecraft.screen instanceof WhitelistPreviewScreen && suspendedForPreview != null) {
            suspendedForPreview.update(payload);
        }
    }

    public static void routeMutationResult(WhitelistReviewMutationResultPacket result) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof WhitelistReviewScreen screen) {
            screen.handleMutationResult(result);
        } else if (minecraft.screen instanceof WhitelistPreviewScreen preview) {
            preview.handleMutationResult(result);
        }
    }

    public static void resumeFromPreview(WhitelistReviewPacket payload, String selectedId) {
        Minecraft minecraft = Minecraft.getInstance();
        WhitelistReviewScreen screen = suspendedForPreview;
        suspendedForPreview = null;
        if (screen == null) {
            minecraft.setScreen(payload == null ? null : new WhitelistReviewScreen(payload, selectedId));
            return;
        }
        if (payload != null && payload != lastPayload) {
            screen.update(payload);
        }
        screen.selectEntryById(selectedId);
        minecraft.setScreen(screen);
    }

    public static void preparePreviewRemoval(String removedId) {
        if (suspendedForPreview != null) {
            suspendedForPreview.prepareRemovalSelection(removedId);
        }
    }

    public static void cancelPreparedPreviewRemoval() {
        if (suspendedForPreview != null) {
            suspendedForPreview.clearPreparedRemoval();
        }
    }

    public void update(WhitelistReviewPacket payload) {
        int previousSelectedIndex = selectedIndex;
        int previousScrollOffset = scrollOffset;
        int previousPageOffset = currentPageOffset();
        String selectedId = selectedEntry() != null ? selectedEntry().id() : "";
        String selectedRecordId = selectedRemovalRecord() != null ? selectedRemovalRecord().recordId() : selectedRemovalId;
        lastPayload = payload;
        this.entries = safeEntries(payload);
        int promptedIndex = indexOf(promptTargetId);
        if (pendingMode == PromptMode.NONE && promptMode != PromptMode.NONE && (promptedIndex < 0
                || !Objects.equals(entries.get(promptedIndex).addedAt(), promptExpectedAddedAt))) {
            clearPrompt();
        }
        this.removalRecords = safeRemovalRecords(payload);

        List<String> ids = historyTab
                ? removalRecords.stream().map(record -> record.recordId()).toList()
                : entries.stream().map(entry -> entry.id()).toList();
        String desiredId = historyTab ? selectedRecordId : selectedId;
        boolean preparedRemovalApplied = !historyTab && !preparedRemovalTargetId.isBlank()
                && indexOf(preparedRemovalTargetId) < 0;
        if (preparedRemovalApplied && !preferredSelectionAfterRemoval.isBlank()) {
            desiredId = preferredSelectionAfterRemoval;
        }

        int fallbackIndex = previousSelectedIndex;
        int restoredScrollOffset = previousScrollOffset;
        if (pageRequestPending && pageRequestDirection != 0) {
            desiredId = "";
            fallbackIndex = 0;
            restoredScrollOffset = 0;
        } else if (currentPageOffset() < previousPageOffset && WhitelistReviewSelection.indexOf(ids, desiredId) < 0) {
            fallbackIndex = ids.size() - 1;
        }
        WhitelistReviewSelection.RefreshState restored = WhitelistReviewSelection.resolveAfterRefresh(
                ids, desiredId, fallbackIndex, restoredScrollOffset, visibleRows());
        selectedIndex = restored.selectedIndex();
        scrollOffset = restored.scrollOffset();
        selectedRemovalId = historyTab && selectedIndex >= 0
                ? removalRecords.get(selectedIndex).recordId()
                : selectedRecordId;
        pageRequestPending = false;
        pageRequestId = 0L;
        pageRequestDirection = 0;
        requestStartedAt = pendingMode == PromptMode.NONE ? 0L : requestStartedAt;
        if (preparedRemovalApplied) {
            clearPreparedRemoval();
        }
        rebuildButtons();
    }

    @Override
    protected void init() {
        rebuildButtons();
    }

    private void rebuildButtons() {
        if (promptField != null) {
            promptDraft = promptField.getValue();
        }
        clearWidgets();
        promptField = null;
        promptSubmitButton = null;
        int bx = boxX();
        int by = boxY();
        int footerGap = 4;
        int footerButtonWidth = Math.max(44, (boxWidth() - 28 - footerGap * 4) / 5);
        int footerX = bx + 14;
        int footerY = by + boxHeight() - 28;
        boolean navigationEnabled = promptMode == PromptMode.NONE
                && pendingMode == PromptMode.NONE && !pageRequestPending && pendingPreviewTargetId.isBlank();

        BlackGoldButton activeTab = new BlackGoldButton(bx + 14, by + 34, 58, 20,
                Component.literal("活动 (" + totalEntries() + ")"), button -> selectTab(false),
                historyTab ? BlackGoldUi.GOLD_DIM : BlackGoldUi.GOLD);
        activeTab.active = navigationEnabled;
        addRenderableWidget(activeTab);
        BlackGoldButton historyTabButton = new BlackGoldButton(bx + 78, by + 34, 70, 20,
                Component.literal("历史 (" + totalRemovals() + ")"), button -> selectTab(true),
                historyTab ? BlackGoldUi.GOLD : BlackGoldUi.GOLD_DIM);
        historyTabButton.active = navigationEnabled;
        addRenderableWidget(historyTabButton);
        BlackGoldButton previousPage = new BlackGoldButton(bx + 154, by + 34, 52, 20,
                Component.literal("上一页"), button -> changePage(-1), BlackGoldUi.GOLD_DIM);
        previousPage.active = navigationEnabled && currentPageOffset() > 0;
        addRenderableWidget(previousPage);
        BlackGoldButton nextPage = new BlackGoldButton(bx + 212, by + 34, 52, 20,
                Component.literal("下一页"), button -> changePage(1), BlackGoldUi.GOLD_DIM);
        nextPage.active = navigationEnabled && currentPageOffset() + currentPageSize() < currentTotal();
        addRenderableWidget(nextPage);
        BlackGoldButton refresh = new BlackGoldButton(footerX, footerY, footerButtonWidth, 20,
                Component.literal("刷新"), button -> refreshCurrentPage(),
                BlackGoldUi.GOLD);
        refresh.active = navigationEnabled;
        addRenderableWidget(refresh);
        footerX += footerButtonWidth + footerGap;
        BlackGoldButton export = new BlackGoldButton(footerX, footerY, footerButtonWidth, 20,
                Component.literal("导出"), button -> request(WhitelistReviewActionPacket.Action.EXPORT, ""),
                BlackGoldUi.GOLD);
        export.active = navigationEnabled;
        addRenderableWidget(export);
        footerX += footerButtonWidth + footerGap;
        BlackGoldButton preview = new BlackGoldButton(footerX, footerY, footerButtonWidth, 20,
                Component.literal("查看"), button -> previewSelected(), BlackGoldUi.GOLD);
        preview.active = navigationEnabled && previewable(selectedEntry());
        addRenderableWidget(preview);
        footerX += footerButtonWidth + footerGap;
        BlackGoldButton comment = new BlackGoldButton(footerX, footerY, footerButtonWidth, 20,
                Component.literal("评论"), button -> openPrompt(PromptMode.COMMENT), BlackGoldUi.GOLD);
        comment.active = navigationEnabled && !historyTab && selectedEntry() != null;
        addRenderableWidget(comment);
        footerX += footerButtonWidth + footerGap;
        BlackGoldButton remove = new BlackGoldButton(footerX, footerY, footerButtonWidth, 20,
                Component.literal("移除"), button -> removeSelected(), 0xFFFF6B6B);
        remove.active = navigationEnabled && !historyTab && selectedEntry() != null;
        addRenderableWidget(remove);
        if (promptMode != PromptMode.NONE) {
            int promptY = by + boxHeight() - 56;
            int cancelWidth = 44;
            int submitWidth = 52;
            int promptWidth = boxWidth() - 28 - cancelWidth - submitWidth - 12;
            Component fieldLabel = Component.literal(
                    promptMode == PromptMode.REMOVE ? "移除备注" : "审核评论");
            promptField = new EditBox(font, bx + 14, promptY, promptWidth, 20, fieldLabel);
            promptField.setMaxLength(256);
            promptField.setValue(promptDraft);
            promptField.setHint(Component.literal(promptMode == PromptMode.REMOVE ? "移除备注（必填）" : "审核评论（必填）"));
            promptField.setEditable(pendingMode == PromptMode.NONE);
            addRenderableWidget(promptField);
            promptSubmitButton = new BlackGoldButton(
                    bx + boxWidth() - 14 - cancelWidth - 6 - submitWidth,
                    promptY, submitWidth, 20,
                    Component.literal(pendingMode == PromptMode.NONE ? "提交" : "处理中"),
                    button -> submitPrompt(), BlackGoldUi.GOLD);
            promptSubmitButton.active = canSubmitPrompt();
            addRenderableWidget(promptSubmitButton);
            BlackGoldButton cancel = new BlackGoldButton(bx + boxWidth() - 14 - cancelWidth,
                    promptY, cancelWidth, 20,
                    Component.literal("取消"), button -> cancelPrompt(), BlackGoldUi.GOLD_DIM);
            cancel.active = pendingMode == PromptMode.NONE;
            addRenderableWidget(cancel);
            promptField.setResponder(value -> {
                promptDraft = value;
                interactionStatus = "";
                if (pendingMode == PromptMode.NONE) {
                    pendingRequestId = 0L;
                }
                if (promptSubmitButton != null) {
                    promptSubmitButton.active = canSubmitPrompt();
                }
            });
            if (pendingMode == PromptMode.NONE) {
                setInitialFocus(promptField);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (pendingMode != PromptMode.NONE || pageRequestPending) {
            return;
        }
        if (promptMode != PromptMode.NONE) {
            cancelPrompt();
            return;
        }
        suspendedForPreview = null;
        pendingPreviewTargetId = "";
        pendingPreviewRequestId = 0L;
        previewRequestStartedAt = 0L;
        super.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        long now = System.currentTimeMillis();
        boolean stateChanged = false;
        if (requestStartedAt > 0L && now - requestStartedAt >= REQUEST_TIMEOUT_MILLIS) {
            if (pendingMode != PromptMode.NONE) {
                pendingMode = PromptMode.NONE;
                pendingTargetId = "";
                clearPreparedRemoval();
                interactionStatus = "未收到服务器确认，输入已保留，可重试";
            } else if (pageRequestPending) {
                interactionStatus = "刷新超时，仍显示原页面，可重试";
            }
            pageRequestPending = false;
            pageRequestId = 0L;
            pageRequestDirection = 0;
            requestStartedAt = 0L;
            stateChanged = true;
        }
        if (!pendingPreviewTargetId.isBlank()
                && now - previewRequestStartedAt >= REQUEST_TIMEOUT_MILLIS) {
            pendingPreviewTargetId = "";
            pendingPreviewRequestId = 0L;
            previewRequestStartedAt = 0L;
            if (suspendedForPreview == this) {
                suspendedForPreview = null;
            }
            interactionStatus = "预览准备超时，可重新尝试";
            stateChanged = true;
        }
        if (stateChanged) {
            rebuildButtons();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        BlackGoldUi.drawBackground(g, width, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        int bx = boxX();
        int by = boxY();
        BlackGoldUi.drawPanel(g, bx, by, boxWidth(), boxHeight());
        drawHeader(g, bx, by, mx, my);
        drawList(g, bx, by, mx, my);
        drawDetails(g, bx, by);
        super.extractRenderState(g, mx, my, pt);
    }

    private void drawHeader(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        BlackGoldUi.drawHeader(g, font, getTitle(), bx, by, boxWidth(), HEADER_H);
        g.text(font, Component.literal(BlackGoldUi.ellipsize(font, pageStatus(), boxWidth() - 28)),
                bx + 14, by + 58, BlackGoldUi.TEXT_SECONDARY, false);
        int cx = bx + boxWidth() - CLOSE_SIZE - 8;
        int cy = by + (HEADER_H - CLOSE_SIZE) / 2;
        closeHovered = mx >= cx && mx <= cx + CLOSE_SIZE && my >= cy && my <= cy + CLOSE_SIZE;
        g.centeredText(font, Component.literal("✕"), cx + CLOSE_SIZE / 2, cy + 4,
                closeHovered ? BlackGoldUi.GOLD : BlackGoldUi.TEXT_SECONDARY);
    }

    private void drawList(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        int itemCount = historyTab ? removalRecords.size() : entries.size();
        if (itemCount == 0) {
            g.centeredText(font, Component.literal(historyTab ? "暂无移除历史" : "当前白名单为空"),
                    bx + boxWidth() / 2, by + ROW_TOP + 8,
                    BlackGoldUi.TEXT_DIM);
            return;
        }
        int rows = Math.min(visibleRows(), itemCount - scrollOffset);
        for (int i = 0; i < rows; i++) {
            int index = scrollOffset + i;
            String id;
            String byName;
            if (historyTab) {
                WhitelistReviewPacket.RemovalRecord record = removalRecords.get(index);
                id = record.entry() == null ? "" : record.entry().id();
                byName = record.removedByName();
            } else {
                WhitelistReviewPacket.Entry entry = entries.get(index);
                id = entry.id();
                byName = entry.addedByName();
            }
            int x = bx + 14;
            int y = by + ROW_TOP + i * ROW_HEIGHT;
            boolean selected = index == selectedIndex;
            boolean hovered = mx >= x && mx <= x + boxWidth() - 28 && my >= y && my <= y + ROW_HEIGHT - 2;
            int bg = selected ? 0xFF263B5F : hovered ? 0xFF202018 : 0xFF151515;
            g.fillGradient(x, y, x + boxWidth() - 28, y + ROW_HEIGHT - 2, bg, bg);
            g.fillGradient(x, y, x + 2, y + ROW_HEIGHT - 2,
                    selected ? BlackGoldUi.GOLD : 0xFF333333, selected ? BlackGoldUi.GOLD : 0xFF333333);
            String prefix = selected ? "▶ " : "  ";
            String label = prefix + (historyTab ? "[移除] " : "[活动] ") + id;
            g.text(font, Component.literal(BlackGoldUi.ellipsize(font, label, boxWidth() - 142)),
                    x + 6, y + 6, selected ? BlackGoldUi.GOLD : BlackGoldUi.TEXT_PRIMARY, false);
            int authorX = bx + boxWidth() - 116;
            g.text(font, Component.literal(BlackGoldUi.ellipsize(font, byName, 90)),
                    authorX, y + 6, BlackGoldUi.TEXT_SECONDARY, false);
        }
        if (itemCount > visibleRows()) {
            drawScrollbar(g, bx, by, mx, my);
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
        int y = by + detailTop();
        int textWidth = boxWidth() - 44;
        g.fillGradient(x, y, bx + boxWidth() - 14, detailBottom(by), 0xFF111111, 0xFF111111);
        if (historyTab) {
            WhitelistReviewPacket.RemovalRecord record = selectedRemovalRecord();
            if (record == null) {
                g.text(font, Component.literal("选择一条历史记录查看详情"), x + 8, y + 10, BlackGoldUi.TEXT_DIM, false);
                return;
            }
            WhitelistReviewPacket.Entry removedEntry = record.entry();
            g.text(font, Component.literal(BlackGoldUi.ellipsize(font,
                    "已移除：" + (removedEntry == null ? "" : removedEntry.id()), textWidth)),
                    x + 8, y + 8, 0xFFFF8A78, false);
            g.text(font, Component.literal(BlackGoldUi.ellipsize(font, "备注：" + record.note(), textWidth)),
                    x + 8, y + 22, BlackGoldUi.TEXT_PRIMARY, false);
            g.text(font, Component.literal(BlackGoldUi.ellipsize(font,
                    "操作者：" + emptyAs(record.removedByName(), "未知") + "  时间：" + record.removedAt(), textWidth)),
                    x + 8, y + 36, BlackGoldUi.TEXT_SECONDARY, false);
            if (removedEntry != null && removedEntry.comments() != null && !removedEntry.comments().isEmpty()
                    && detailBottom(by) - y >= 60) {
                WhitelistReviewPacket.Comment latest = removedEntry.comments().get(removedEntry.comments().size() - 1);
                g.text(font, Component.literal(BlackGoldUi.ellipsize(font,
                        "删除前评论：" + latest.authorName() + "：" + latest.text(), textWidth)),
                        x + 8, y + 50, BlackGoldUi.TEXT_DIM, false);
            }
            return;
        }
        if (entry == null) {
            g.text(font, Component.literal("选择一个条目查看详情"), x + 8, y + 10, BlackGoldUi.TEXT_DIM, false);
            return;
        }
        g.text(font, Component.literal(BlackGoldUi.ellipsize(font, "资源：" + entry.id(), textWidth)),
                x + 8, y + 8, BlackGoldUi.GOLD, false);
        g.text(font, Component.literal(BlackGoldUi.ellipsize(font,
                "添加者：" + emptyAs(entry.addedByName(), "未知") + "  时间：" + entry.addedAt(), textWidth)),
                x + 8, y + 21, BlackGoldUi.TEXT_SECONDARY, false);
        List<WhitelistReviewPacket.Comment> comments = entry.comments() == null ? List.of() : entry.comments();
        if (!comments.isEmpty() && detailBottom(by) - y < 84) {
            WhitelistReviewPacket.Comment latest = comments.get(comments.size() - 1);
            g.text(font, Component.literal(BlackGoldUi.ellipsize(font,
                    "最新评论：" + latest.authorName() + "：" + latest.text(), textWidth)),
                    x + 8, y + 34, BlackGoldUi.TEXT_DIM, false);
            return;
        }
        g.text(font, Component.literal(BlackGoldUi.ellipsize(font,
                "原始输入：" + emptyAs(entry.originalInput(), entry.id()), textWidth)),
                x + 8, y + 34, BlackGoldUi.TEXT_DIM, false);
        int line = 48;
        int availableCommentHeight = detailBottom(by) - (y + 61);
        int maxComments = availableCommentHeight < 9 ? 0 : Math.min(2, 1 + (availableCommentHeight - 9) / 13);
        if (!comments.isEmpty() && maxComments > 0) {
            g.text(font, Component.literal("最近评论："), x + 8, y + line, BlackGoldUi.GOLD, false);
            line += 13;
            int start = Math.max(0, comments.size() - maxComments);
            for (int i = start; i < comments.size(); i++) {
                WhitelistReviewPacket.Comment comment = comments.get(i);
                g.text(font, Component.literal(BlackGoldUi.ellipsize(font,
                                comment.authorName() + "：" + comment.text(), textWidth)),
                        x + 8, y + line, BlackGoldUi.TEXT_SECONDARY, false);
                line += 13;
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean cancelled) {
        if (cancelled) {
            return false;
        }
        int bx = boxX();
        int by = boxY();
        int cx = bx + boxWidth() - CLOSE_SIZE - 8;
        int cy = by + (HEADER_H - CLOSE_SIZE) / 2;
        if (event.x() >= cx && event.x() <= cx + CLOSE_SIZE && event.y() >= cy && event.y() <= cy + CLOSE_SIZE) {
            onClose();
            return true;
        }
        if (promptMode != PromptMode.NONE || pendingMode != PromptMode.NONE || pageRequestPending || !pendingPreviewTargetId.isBlank()) {
            return super.mouseClicked(event, cancelled);
        }
        int itemCount = historyTab ? removalRecords.size() : entries.size();
        if (event.button() == 0 && itemCount > visibleRows() && inScrollbar(event.x(), event.y(), bx, by)) {
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
        if (event.x() >= listX && event.x() <= listX + boxWidth() - 28
                && event.y() >= listY && event.y() < listY + visibleRows() * ROW_HEIGHT) {
            int row = ((int) event.y() - listY) / ROW_HEIGHT;
            int index = scrollOffset + row;
            if (index >= 0 && index < itemCount) {
                selectedIndex = index;
                if (historyTab) {
                    selectedRemovalId = removalRecords.get(index).recordId();
                }
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
    public boolean keyPressed(KeyEvent event) {
        if (!pendingPreviewTargetId.isBlank()) {
            if (event.key() == 256) {
                onClose();
                return true;
            }
            return super.keyPressed(event);
        }
        if (pendingMode != PromptMode.NONE || pageRequestPending) {
            return event.key() == 256 || super.keyPressed(event);
        }
        if (promptMode != PromptMode.NONE) {
            if (event.key() == 256) {
                cancelPrompt();
                return true;
            }
            if ((event.key() == 257 || event.key() == 335) && getFocused() == promptField) {
                submitPrompt();
                return true;
            }
            return super.keyPressed(event);
        }
        switch (event.key()) {
            case 265 -> moveSelection(-1);
            case 264 -> moveSelection(1);
            case 266 -> moveSelection(-visibleRows());
            case 267 -> moveSelection(visibleRows());
            case 268 -> selectIndex(0);
            case 269 -> selectIndex((historyTab ? removalRecords.size() : entries.size()) - 1);
            case 257, 335 -> previewSelected();
            case 261 -> removeSelected();
            default -> {
                return super.keyPressed(event);
            }
        }
        return true;
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
        if (promptMode != PromptMode.NONE || pendingMode != PromptMode.NONE || pageRequestPending || !pendingPreviewTargetId.isBlank()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int itemCount = historyTab ? removalRecords.size() : entries.size();
        if (itemCount <= visibleRows() || scrollY == 0.0D || !inList(mouseX, mouseY)) {
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
            openPrompt(PromptMode.REMOVE);
        }
    }

    private void previewSelected() {
        WhitelistReviewPacket.Entry entry = selectedEntry();
        if (entry == null) {
            return;
        }
        if (!previewable(entry)) {
            interactionStatus = "直播条目暂不支持审核预览";
            rebuildButtons();
            return;
        }
        draggingScrollbar = false;
        suspendedForPreview = this;
        pendingPreviewTargetId = entry.id();
        pendingPreviewRequestId = WhitelistReviewActionPacket.nextClientRequestId();
        previewRequestStartedAt = System.currentTimeMillis();
        interactionStatus = "";
        rebuildButtons();
        ClientPacketDistributor.sendToServer(new WhitelistReviewActionPacket(
                WhitelistReviewActionPacket.Action.PREVIEW, entry.id(), 0L, pendingPreviewRequestId));
    }

    private void request(WhitelistReviewActionPacket.Action action, String value) {
        ClientPacketDistributor.sendToServer(new WhitelistReviewActionPacket(action, value));
    }

    boolean acceptPreviewResponse(com.zhongbai233.net_music_can_play_bili.network.WhitelistPreviewPacket payload) {
        if (payload == null || pendingPreviewTargetId.isBlank()
                || pendingPreviewRequestId <= 0L || pendingPreviewRequestId != payload.requestId()
                || !WhitelistReviewSelection.matchesPreview(pendingPreviewTargetId, payload.rawUrl())) {
            return false;
        }
        pendingPreviewTargetId = "";
        pendingPreviewRequestId = 0L;
        previewRequestStartedAt = 0L;
        interactionStatus = "";
        return true;
    }

    private void refreshCurrentPage() {
        beginPageRequest(0, entryOffset(), removalOffset());
    }

    private void changePage(int direction) {
        int nextEntryOffset = entryOffset();
        int nextRemovalOffset = removalOffset();
        if (historyTab) {
            nextRemovalOffset = WhitelistReviewSelection.clampPageOffset(
                    nextRemovalOffset + direction * WhitelistReviewPacket.REMOVAL_PAGE_SIZE,
                    totalRemovals(), WhitelistReviewPacket.REMOVAL_PAGE_SIZE);
        } else {
            nextEntryOffset = WhitelistReviewSelection.clampPageOffset(
                    nextEntryOffset + direction * WhitelistReviewPacket.ENTRY_PAGE_SIZE,
                    totalEntries(), WhitelistReviewPacket.ENTRY_PAGE_SIZE);
        }
        if (nextEntryOffset != entryOffset() || nextRemovalOffset != removalOffset()) {
            beginPageRequest(direction, nextEntryOffset, nextRemovalOffset);
        }
    }

    private void beginPageRequest(int direction, int nextEntryOffset, int nextRemovalOffset) {
        pageRequestPending = true;
        pageRequestId = WhitelistReviewActionPacket.nextClientRequestId();
        pageRequestDirection = Integer.compare(direction, 0);
        requestStartedAt = System.currentTimeMillis();
        interactionStatus = "";
        rebuildButtons();
        requestPage(nextEntryOffset, nextRemovalOffset, pageRequestId);
    }

    private void requestPage(int nextEntryOffset, int nextRemovalOffset, long requestId) {
        ClientPacketDistributor.sendToServer(new WhitelistReviewActionPacket(
                WhitelistReviewActionPacket.Action.REFRESH, "", 0L, "", "",
                Math.max(0, nextEntryOffset), Math.max(0, nextRemovalOffset), requestId));
    }

    private void selectTab(boolean history) {
        if (historyTab == history) {
            return;
        }
        historyTab = history;
        selectedIndex = 0;
        scrollOffset = 0;
        interactionStatus = "";
        if (history) {
            selectedRemovalId = selectedRemovalRecord() == null ? "" : selectedRemovalRecord().recordId();
        }
        rebuildButtons();
    }

    private void cancelPrompt() {
        clearPrompt();
        rebuildButtons();
    }

    private void openPrompt(PromptMode mode) {
        WhitelistReviewPacket.Entry entry = selectedEntry();
        if (entry == null || pendingMode != PromptMode.NONE || pageRequestPending) {
            return;
        }
        promptMode = mode;
        promptTargetId = entry.id();
        promptExpectedAddedAt = entry.addedAt();
        promptDraft = "";
        pendingRequestId = 0L;
        interactionStatus = "";
        promptField = null;
        ensureSelectedVisible();
        rebuildButtons();
    }

    private void submitPrompt() {
        String text = promptField == null ? "" : promptField.getValue().trim();
        promptDraft = text;
        if (!canSubmitPrompt()) {
            return;
        }
        WhitelistReviewActionPacket.Action action = promptMode == PromptMode.REMOVE
                ? WhitelistReviewActionPacket.Action.REMOVE
                : WhitelistReviewActionPacket.Action.COMMENT;
        pendingMode = promptMode;
        pendingTargetId = promptTargetId;
        if (pendingRequestId <= 0L) {
            pendingRequestId = WhitelistReviewActionPacket.nextClientRequestId();
        }
        if (pendingMode == PromptMode.REMOVE) {
            prepareRemovalSelection(promptTargetId);
        }
        requestStartedAt = System.currentTimeMillis();
        interactionStatus = "";
        rebuildButtons();
        ClientPacketDistributor.sendToServer(new WhitelistReviewActionPacket(action, promptTargetId,
                0L, text, promptExpectedAddedAt, entryOffset(), removalOffset(), pendingRequestId));
    }

    private boolean canSubmitPrompt() {
        if (promptMode == PromptMode.NONE || pendingMode != PromptMode.NONE
                || promptDraft == null || promptDraft.trim().isEmpty()) {
            return false;
        }
        int targetIndex = indexOf(promptTargetId);
        return targetIndex >= 0
                && Objects.equals(entries.get(targetIndex).addedAt(), promptExpectedAddedAt);
    }

    private void handleMutationResult(WhitelistReviewMutationResultPacket result) {
        if (result == null) {
            return;
        }
        if (result.mutation() == WhitelistReviewMutationResultPacket.Mutation.REFRESH) {
            if (pageRequestPending && pageRequestId > 0L && pageRequestId == result.requestId()) {
                pageRequestPending = false;
                pageRequestId = 0L;
                pageRequestDirection = 0;
                requestStartedAt = 0L;
                interactionStatus = result.message();
                rebuildButtons();
            }
            return;
        }
        if (result.mutation() == WhitelistReviewMutationResultPacket.Mutation.PREVIEW) {
            if (!pendingPreviewTargetId.isBlank()
                    && pendingPreviewRequestId > 0L
                    && pendingPreviewRequestId == result.requestId()
                    && Objects.equals(pendingPreviewTargetId, result.targetId())) {
                pendingPreviewTargetId = "";
                pendingPreviewRequestId = 0L;
                previewRequestStartedAt = 0L;
                if (suspendedForPreview == this) {
                    suspendedForPreview = null;
                }
                interactionStatus = result.message();
                rebuildButtons();
            }
            return;
        }
        if (pendingMode == PromptMode.NONE
                || !Objects.equals(pendingTargetId, result.targetId())
                || pendingRequestId != result.requestId()) {
            return;
        }
        WhitelistReviewMutationResultPacket.Mutation expected = pendingMode == PromptMode.REMOVE
                ? WhitelistReviewMutationResultPacket.Mutation.REMOVE
                : WhitelistReviewMutationResultPacket.Mutation.COMMENT;
        if (result.mutation() != expected) {
            return;
        }
        pendingMode = PromptMode.NONE;
        pendingTargetId = "";
        pendingRequestId = 0L;
        requestStartedAt = 0L;
        if (result.successful()) {
            clearPrompt();
        } else {
            clearPreparedRemoval();
        }
        interactionStatus = result.message();
        rebuildButtons();
    }

    private void prepareRemovalSelection(String removedId) {
        preparedRemovalTargetId = removedId == null ? "" : removedId;
        preferredSelectionAfterRemoval = WhitelistReviewSelection.adjacentIdAfterRemoval(
                entries.stream().map(entry -> entry.id()).toList(), preparedRemovalTargetId);
    }

    private void clearPreparedRemoval() {
        preparedRemovalTargetId = "";
        preferredSelectionAfterRemoval = "";
    }

    private void selectEntryById(String selectedId) {
        if (selectedId == null || selectedId.isBlank()) {
            return;
        }
        int index = indexOf(selectedId);
        if (index < 0) {
            return;
        }
        historyTab = false;
        selectedIndex = index;
        ensureSelectedVisible();
    }

    private void moveSelection(int delta) {
        int itemCount = historyTab ? removalRecords.size() : entries.size();
        if (itemCount == 0) {
            return;
        }
        int start = selectedIndex < 0 ? 0 : selectedIndex;
        selectIndex(Math.max(0, Math.min(itemCount - 1, start + delta)));
    }

    private void selectIndex(int index) {
        int itemCount = historyTab ? removalRecords.size() : entries.size();
        if (index < 0 || index >= itemCount) {
            return;
        }
        selectedIndex = index;
        if (historyTab) {
            selectedRemovalId = removalRecords.get(index).recordId();
        }
        ensureSelectedVisible();
        interactionStatus = "";
        rebuildButtons();
    }

    private void clearPrompt() {
        promptMode = PromptMode.NONE;
        promptTargetId = "";
        promptExpectedAddedAt = "";
        promptDraft = "";
        pendingRequestId = 0L;
        promptField = null;
    }

    private WhitelistReviewPacket.Entry selectedEntry() {
        if (historyTab) {
            return null;
        }
        return selectedIndex >= 0 && selectedIndex < entries.size() ? entries.get(selectedIndex) : null;
    }

    private WhitelistReviewPacket.RemovalRecord selectedRemovalRecord() {
        return selectedIndex >= 0 && selectedIndex < removalRecords.size() ? removalRecords.get(selectedIndex) : null;
    }

    private int indexOf(String id) {
        return WhitelistReviewSelection.indexOf(entries.stream()
            .map(entry -> entry.id()).toList(), id);
    }

    private void clampScrollOffset() {
        int max = Math.max(0, (historyTab ? removalRecords.size() : entries.size()) - visibleRows());
        scrollOffset = Math.max(0, Math.min(max, scrollOffset));
    }

    private void ensureSelectedVisible() {
        if (selectedIndex >= 0) {
            if (selectedIndex < scrollOffset) {
                scrollOffset = selectedIndex;
            } else if (selectedIndex >= scrollOffset + visibleRows()) {
                scrollOffset = Math.max(0, selectedIndex - visibleRows() + 1);
            }
        }
    }

    private void scrollToScrollbarPosition(double mouseY, int by) {
        int maxScroll = Math.max(0, (historyTab ? removalRecords.size() : entries.size()) - visibleRows());
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
        return mouseX >= bx + 14 && mouseX <= bx + boxWidth() - 14
                && mouseY >= by + ROW_TOP && mouseY < by + ROW_TOP + visibleRows() * ROW_HEIGHT;
    }

    private boolean inScrollbar(double mouseX, double mouseY, int bx, int by) {
        int x = scrollbarX(bx);
        int y = scrollbarY(by);
        return mouseX >= x && mouseX <= x + SCROLLBAR_WIDTH
                && mouseY >= y && mouseY <= y + scrollbarHeight();
    }

    private int scrollbarX(int bx) {
        return bx + boxWidth() - 20;
    }

    private int scrollbarY(int by) {
        return by + ROW_TOP;
    }

    private int scrollbarHeight() {
        return visibleRows() * ROW_HEIGHT - 2;
    }

    private int scrollbarThumbHeight() {
        return Math.max(SCROLLBAR_MIN_THUMB_HEIGHT,
                scrollbarHeight() * visibleRows() / Math.max(visibleRows(),
                        historyTab ? removalRecords.size() : entries.size()));
    }

    private int scrollbarThumbY(int by) {
        int maxScroll = Math.max(0, (historyTab ? removalRecords.size() : entries.size()) - visibleRows());
        int travel = scrollbarHeight() - scrollbarThumbHeight();
        return scrollbarY(by) + (maxScroll == 0 ? 0 : Math.round((float) travel * scrollOffset / maxScroll));
    }

    private int boxX() {
        return (width - boxWidth()) / 2;
    }

    private int boxY() {
        return (height - boxHeight()) / 2;
    }

    private int boxWidth() {
        return Math.min(PREFERRED_BOX_W, Math.max(MIN_BOX_W, width - 16));
    }

    private int boxHeight() {
        return Math.min(PREFERRED_BOX_H, Math.max(MIN_BOX_H, height - 16));
    }

    private int visibleRows() {
        int detailBottomReserve = promptMode == PromptMode.NONE ? 36 : 62;
        int available = boxHeight() - ROW_TOP - 10 - MIN_DETAIL_HEIGHT - detailBottomReserve;
        return Math.max(1, Math.min(MAX_VISIBLE_ROWS, available / ROW_HEIGHT));
    }

    private int detailTop() {
        return ROW_TOP + visibleRows() * ROW_HEIGHT + 10;
    }

    private int detailBottom(int by) {
        return by + boxHeight() - (promptMode == PromptMode.NONE ? 36 : 62);
    }

    private int entryOffset() {
        return lastPayload == null ? 0 : Math.max(0, lastPayload.entryOffset());
    }

    private int removalOffset() {
        return lastPayload == null ? 0 : Math.max(0, lastPayload.removalOffset());
    }

    private int totalEntries() {
        return lastPayload == null ? entries.size() : Math.max(entries.size(), lastPayload.totalEntries());
    }

    private int totalRemovals() {
        return lastPayload == null
                ? removalRecords.size()
                : Math.max(removalRecords.size(), lastPayload.totalRemovalRecords());
    }

    private int currentPageOffset() {
        return historyTab ? removalOffset() : entryOffset();
    }

    private int currentPageSize() {
        return historyTab ? removalRecords.size() : entries.size();
    }

    private int currentTotal() {
        return historyTab ? totalRemovals() : totalEntries();
    }

    private String pageStatus() {
        int total = currentTotal();
        int start = total <= 0 ? 0 : Math.min(total, currentPageOffset() + 1);
        int end = total <= 0 ? 0 : Math.min(total, currentPageOffset() + currentPageSize());
        String status = (historyTab ? "历史 " : "活动 ") + start + "-" + end + "/" + total;
        if (pendingMode == PromptMode.REMOVE) {
            return status + " · 正在移除…";
        }
        if (pendingMode == PromptMode.COMMENT) {
            return status + " · 正在提交评论…";
        }
        if (!pendingPreviewTargetId.isBlank()) {
            return status + " · 正在准备预览…";
        }
        if (pageRequestPending) {
            return status + " · 正在刷新…";
        }
        return interactionStatus.isBlank() ? status : status + " · " + interactionStatus;
    }

    private static List<WhitelistReviewPacket.Entry> safeEntries(WhitelistReviewPacket payload) {
        return payload == null || payload.entries() == null ? List.of() : payload.entries();
    }

    private static List<WhitelistReviewPacket.RemovalRecord> safeRemovalRecords(WhitelistReviewPacket payload) {
        return payload == null || payload.removalRecords() == null ? List.of() : payload.removalRecords();
    }

    private static String emptyAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean previewable(WhitelistReviewPacket.Entry entry) {
        return entry != null && !"live".equalsIgnoreCase(entry.type());
    }
}
