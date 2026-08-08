package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.ClientPlayerPreferences;
import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/** 首次打开中控台时显示的客户端说明书。 */
public final class ControlConsoleGuideScreen extends Screen {
    private static final int PANEL_W = 390;
    private static final int PANEL_H = 238;
    private static final int HEADER_H = 30;

    private final BlockPos consolePos;
    private final Level openingLevel;
    private final LocalPlayer openingPlayer;
    private Checkbox doNotShowAgain;
    private boolean transferringToEditor;

    public ControlConsoleGuideScreen(BlockPos consolePos) {
        super(Component.translatable("gui.net_music_can_play_bili.control_console.guide.title"));
        this.consolePos = java.util.Objects.requireNonNull(consolePos, "consolePos").immutable();
        Minecraft minecraft = Minecraft.getInstance();
        this.openingLevel = minecraft.level;
        this.openingPlayer = minecraft.player;
    }

    @Override
    protected void init() {
        int x = panelX();
        int y = panelY();
        doNotShowAgain = addRenderableWidget(Checkbox.builder(
                Component.translatable("gui.net_music_can_play_bili.control_console.guide.do_not_show_again"), font)
                .pos(x + 28, y + 164).selected(false).maxWidth(PANEL_W - 56).build());
        addRenderableWidget(new BlackGoldButton(x + 28, y + 198, 160, 22,
                Component.translatable("gui.net_music_can_play_bili.control_console.guide.enter"),
                button -> enterEditor(), BlackGoldUi.GOLD));
        addRenderableWidget(new BlackGoldButton(x + 202, y + 198, 160, 22,
                Component.translatable("gui.net_music_can_play_bili.control_console.guide.cancel"),
                button -> onClose(), BlackGoldUi.GOLD_DIM));
    }

    @Override
    public void tick() {
        super.tick();
        if (!validHost()) {
            onClose();
        } else {
            ControlConsoleClient.tickLease(consolePos);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        BlackGoldUi.drawBackground(graphics, width, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = panelX();
        int y = panelY();
        BlackGoldUi.drawPanel(graphics, x, y, PANEL_W, PANEL_H);
        BlackGoldUi.drawHeader(graphics, font, getTitle(), x, y, PANEL_W, HEADER_H);
        graphics.text(font, Component.translatable("gui.net_music_can_play_bili.control_console.guide.line_1"),
                x + 28, y + 50, BlackGoldUi.TEXT_PRIMARY);
        graphics.text(font, Component.translatable("gui.net_music_can_play_bili.control_console.guide.line_2"),
                x + 28, y + 72, BlackGoldUi.TEXT_SECONDARY);
        graphics.text(font, Component.translatable("gui.net_music_can_play_bili.control_console.guide.line_3"),
                x + 28, y + 94, BlackGoldUi.TEXT_SECONDARY);
        graphics.text(font, Component.translatable("gui.net_music_can_play_bili.control_console.guide.line_4"),
                x + 28, y + 116, BlackGoldUi.TEXT_SECONDARY);
        graphics.text(font, Component.translatable("gui.net_music_can_play_bili.control_console.guide.line_5"),
                x + 28, y + 138, BlackGoldUi.TEXT_DIM);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (!transferringToEditor) {
            ControlConsoleClient.releaseLease(consolePos);
        }
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    private void enterEditor() {
        if (!validHost() || minecraft == null || openingPlayer == null) {
            onClose();
            return;
        }
        if (doNotShowAgain != null && doNotShowAgain.selected()) {
            ClientPlayerPreferences.defaults().dismissControlConsoleGuide(openingPlayer.getUUID());
        }
        transferringToEditor = true;
        ControlConsoleClient.openEditor(consolePos);
    }

    private boolean validHost() {
        if (minecraft == null || minecraft.level != openingLevel || minecraft.player != openingPlayer
                || openingPlayer == null || !openingPlayer.isAlive() || openingLevel == null) {
            return false;
        }
        return openingPlayer.distanceToSqr(consolePos.getX() + 0.5D, consolePos.getY() + 0.5D,
                consolePos.getZ() + 0.5D) <= 64.0D
                && openingLevel.hasChunk(Math.floorDiv(consolePos.getX(), 16),
                    Math.floorDiv(consolePos.getZ(), 16))
                && openingLevel.getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity;
    }

    private int panelX() {
        return (width - PANEL_W) / 2;
    }

    private int panelY() {
        return (height - PANEL_H) / 2;
    }
}