package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.menu.MediaToolBindingMenu;
import com.zhongbai233.net_music_can_play_bili.network.MediaToolClearBindingPacket;
import com.zhongbai233.net_music_can_play_bili.network.MediaToolConfirmBindingPacket;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public class MediaToolBindingScreen extends AbstractContainerScreen<MediaToolBindingMenu> {
    private static final int WIDTH = 220;
    private static final int HEIGHT = 210;

    public MediaToolBindingScreen(MediaToolBindingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        int lx = leftPos, ty = topPos;
        addRenderableWidget(new BlackGoldButton(lx + 153, ty + 62, 20, 20,
                Component.literal("↓"),
                button -> ClientPacketDistributor.sendToServer(new MediaToolConfirmBindingPacket()),
                BlackGoldUi.GOLD));
        addRenderableWidget(new BlackGoldButton(lx + 22, ty + 80, 70, 20,
                Component.translatable("gui.net_music_can_play_bili.media_tool.clear"),
                button -> ClientPacketDistributor.sendToServer(new MediaToolClearBindingPacket()),
                BlackGoldUi.GOLD));
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

        int contentTop = ty + 30;
        int contentBottom = ty + 112;
        g.fillGradient(lx + 10, contentTop, lx + imageWidth - 10, contentBottom, 0xAA181818, 0xAA202018);

        int dividerY = contentBottom + 2;
        g.fillGradient(lx + 10, dividerY, lx + imageWidth - 10, dividerY + 1, BlackGoldUi.GOLD_DIM,
                BlackGoldUi.GOLD_DIM);

        BlackGoldUi.drawSlotFrame(g, lx + 42, ty + 42, BlackGoldUi.GOLD_DIM);
        BlackGoldUi.drawSlotFrame(g, lx + 154, ty + 42, 0xFF6688AA);
        BlackGoldUi.drawSlotFrame(g, lx + 154, ty + 84, 0xFF66AA77);

        Component targetLabel = Component.translatable(menu.usesManualMp4TargetSlot()
                ? menu.targetKind() == MediaToolBindingMenu.TargetKind.PAD
                        ? "gui.net_music_can_play_bili.media_tool.pad_input"
                        : "gui.net_music_can_play_bili.media_tool.mp4_input"
                : "gui.net_music_can_play_bili.media_tool.target");
        g.centeredText(font, targetLabel, lx + 52, ty + 28, BlackGoldUi.GOLD);

        g.text(font, Component.translatable("gui.net_music_can_play_bili.media_tool.bound_count",
                menu.headphoneBindingCount(), menu.holographicBindingCount(), menu.totalTargetBindingCount()),
                lx + 16, ty + 68, BlackGoldUi.TEXT_SECONDARY, false);

        g.centeredText(font, Component.translatable("gui.net_music_can_play_bili.media_tool.input"),
                lx + 164, ty + 28, BlackGoldUi.TEXT_PRIMARY);
        g.centeredText(font, Component.translatable("gui.net_music_can_play_bili.media_tool.output"),
                lx + 164, ty + 108, BlackGoldUi.TEXT_PRIMARY);

        super.extractRenderState(g, mx, my, pt);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {
    }
}
