package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.blockentity.LyricProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.network.LyricProjectorConfigPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public class LyricProjectorScreen extends BlackGoldScreen {
    private CycleWidget modeBtn;
    private CycleWidget aiBtn;

    public LyricProjectorScreen(BlockPos pos) {
        super(Component.literal("\u266b \u6b4c\u8bcd\u6295\u5f71\u4eea"), pos);
    }

    @Override
    protected int boxH() {
        return 336;
    }

    @Override
    protected void buildWidgets() {
        int bx = boxX(), by = boxY();
        int sliderX = bx + PAD + 54;
        int resetX = sliderX + SLIDER_W + 4 + VAL_W + 3;
        int rowY = by + HEADER_H + 54;
        LyricProjectorBlockEntity be = getProjectorBE();

        ConfigSlider yawS = addConfigSlider(sliderX, rowY, 0, 360,
                be != null ? be.getProjectionYaw() : 180,
                v -> {
                    if (be != null)
                        be.setProjectionYaw(v);
                });
        addResetButton(resetX, rowY, 180f, yawS);
        rowY += 26;

        ConfigSlider pitchS = addConfigSlider(sliderX, rowY, -90, 90,
                be != null ? be.getProjectionPitch() : 0,
                v -> {
                    if (be != null)
                        be.setProjectionPitch(v);
                });
        addResetButton(resetX, rowY, 0f, pitchS);
        rowY += 26;

        ConfigSlider heightS = addConfigSlider(sliderX, rowY, -5.0f, 5.0f,
                be != null ? be.getProjectionHeight() : 1.2f,
                v -> {
                    if (be != null)
                        be.setProjectionHeight(v);
                });
        addResetButton(resetX, rowY, 1.2f, heightS);
        rowY += 26;

        ConfigSlider distXS = addConfigSlider(sliderX, rowY, -5.0f, 5.0f,
                be != null ? be.getProjectionDistanceX() : 0.0f,
                v -> {
                    if (be != null)
                        be.setProjectionDistanceX(v);
                });
        addResetButton(resetX, rowY, 0f, distXS);
        rowY += 26;

        ConfigSlider distZS = addConfigSlider(sliderX, rowY, -5.0f, 5.0f,
                be != null ? be.getProjectionDistanceZ() : 0.0f,
                v -> {
                    if (be != null)
                        be.setProjectionDistanceZ(v);
                });
        addResetButton(resetX, rowY, 0f, distZS);
        rowY += 26;

        ConfigSlider scaleS = addConfigSlider(sliderX, rowY, 0.25f, 3.0f,
                be != null ? be.getProjectionScale() : 1.0f,
                v -> {
                    if (be != null)
                        be.setProjectionScale(v);
                });
        addResetButton(resetX, rowY, 1.0f, scaleS);
        rowY += 26;

        int mode = be != null ? be.getProjectionMode() : 0;
        modeBtn = addCycleWidget(sliderX, rowY,
                List.of("\u9759\u6001", "\u8f6e\u6362\u00b7\u4e3b", "\u8f6e\u6362\u00b7\u526f"), mode,
                v -> {
                    if (be != null)
                        be.setProjectionMode(v);
                });
        rowY += 30;

        boolean allowAi = be != null && be.getAllowAi();
        aiBtn = addCycleWidget(sliderX, rowY,
                List.of("AI\u5b57\u5e55\uff1a\u5173", "AI\u5b57\u5e55\uff1a\u5f00"),
                allowAi ? 1 : 0,
                v -> {
                    if (be != null)
                        be.setAllowAi(v == 1);
                });
    }

    @Override
    protected void onSave() {
        LyricProjectorBlockEntity be = getProjectorBE();
        if (be != null && minecraft != null && minecraft.getConnection() != null) {
            minecraft.getConnection().send(new LyricProjectorConfigPacket(
                    blockPos,
                    be.getProjectionYaw(), be.getProjectionPitch(), be.getProjectionScale(),
                    be.getProjectionHeight(), be.getProjectionDistanceX(), be.getProjectionDistanceZ(),
                    be.getProjectionMode(),
                    be.getAllowAi()));
        }
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        drawLinkInfo(g, bx, by);
        drawLabels(g, bx, by);
        drawModeOverlay(g);
        drawAiOverlay(g);
    }

    private LyricProjectorBlockEntity getProjectorBE() {
        if (minecraft == null || minecraft.level == null)
            return null;
        BlockEntity be = minecraft.level.getBlockEntity(blockPos);
        return be instanceof LyricProjectorBlockEntity p ? p : null;
    }

    private void drawLinkInfo(GuiGraphicsExtractor g, int bx, int by) {
        int cx = bx + BOX_W / 2, iy = by + HEADER_H + 10;
        var level = Minecraft.getInstance().level;
        if (level == null)
            return;
        BlockEntity be = level.getBlockEntity(blockPos);
        if (!(be instanceof LyricProjectorBlockEntity p))
            return;
        BlockPos linked = p.getLinkedTurntablePos();
        if (linked == null) {
            g.centeredText(font, Component.literal("\u672a\u8fde\u63a5"), cx, iy, TEXT_DIM);
            return;
        }
        String info = String.format("\u5df2\u8fde\u63a5 (%d,%d,%d)",
                linked.getX(), linked.getY(), linked.getZ());
        if (level.getBlockEntity(linked) instanceof ModernTurntableBlockEntity t
                && !t.getSongName().isBlank()) {
            String s = t.getSongName();
            info = s.length() > 26 ? s.substring(0, 24) + ".." : s;
        }
        g.centeredText(font, Component.literal(info), cx, iy, GOLD);
    }

    private void drawLabels(GuiGraphicsExtractor g, int bx, int by) {
        int lx = bx + PAD, ry = by + HEADER_H + 58;
        String[] labels = { "\u6c34\u5e73\u671d\u5411", "\u4fef\u4ef0\u89d2\u5ea6",
                "\u6295\u5f71\u9ad8\u5ea6", "\u6295\u5f71X\u8f74", "\u6295\u5f71Z\u8f74",
                "\u6587\u5b57\u5927\u5c0f", "\u8bed\u8a00/\u6a21\u5f0f", "AI\u5b57\u5e55" };
        for (String lb : labels) {
            g.centeredText(font, Component.literal(lb), lx + 27, ry, TEXT_SECONDARY);
            ry += 26;
        }
    }

    private void drawModeOverlay(GuiGraphicsExtractor g) {
        if (modeBtn == null)
            return;
        int x = modeBtn.getX(), y = modeBtn.getY(), w = modeBtn.getWidth(), h = modeBtn.getHeight();
        g.fillGradient(x, y, x + w, y + h, GOLD & 0x30FFFFFF, GOLD & 0x18FFFFFF);
        g.centeredText(font, Component.literal("\u25c8 " + modeBtn.currentOption()),
                x + w / 2, y + 5, GOLD);
    }

    private void drawAiOverlay(GuiGraphicsExtractor g) {
        if (aiBtn == null)
            return;
        int x = aiBtn.getX(), y = aiBtn.getY(), w = aiBtn.getWidth(), h = aiBtn.getHeight();
        g.fillGradient(x, y, x + w, y + h, GOLD & 0x30FFFFFF, GOLD & 0x18FFFFFF);
        g.centeredText(font, Component.literal("\u25c8 " + aiBtn.currentOption()),
                x + w / 2, y + 5, GOLD);
    }
}
