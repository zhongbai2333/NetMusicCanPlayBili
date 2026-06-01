package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.network.SpeakerConfigPacket;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 音响方块 GUI — 7.1.4 单选声道 / 音量 / JOC 融合
 */
public class SpeakerScreen extends BlackGoldScreen {
    private static final int CH_BTN_W = 24, CH_BTN_H = 16;

    private int channelIndex = SpeakerBlockEntity.CH_NONE;
    private float volume = 1.0f;
    private boolean autoMixJoc;
    private final Map<Integer, BlackGoldButton> chButtons = new LinkedHashMap<>();
    private BlackGoldButton noneBtn, jocBtn;

    public SpeakerScreen(BlockPos pos) {
        super(Component.translatable("gui.net_music_can_play_bili.speaker"), pos);
    }

    @Override
    protected void buildWidgets() {
        chButtons.clear();
        int bx = boxX(), by = boxY();
        SpeakerBlockEntity be = getSpeakerBE();
        channelIndex = be != null ? be.getChannelIndex() : SpeakerBlockEntity.CH_NONE;
        volume = be != null ? be.getVolume() : 1.0f;
        autoMixJoc = be != null ? be.isAutoMixJoc() : false;

        int gx = bx + PAD + 14, gy = by + HEADER_H + 10;
        int[][] layout = {
                { -1, -1, 8, -1, -1, -1, 9, -1 },
                { 0, -1, 2, -1, 1, -1, 3, -1 },
                { 4, -1, 5, -1, 6, -1, 7, -1 },
                { -1, -1, 10, -1, -1, -1, 11, -1 },
        };
        String[] labels = { "", "", "Ltf", "", "", "", "Rtf", "", "L", "", "C", "", "R", "", "LFE", "",
                "Ls", "", "Rs", "", "Lrs", "", "Rrs", "", "", "", "Ltr", "", "", "", "Rtr", "" };

        for (int row = 0; row < layout.length; row++) {
            for (int col = 0; col < 8; col++) {
                int ch = layout[row][col];
                if (ch < 0)
                    continue;
                addChBtn(gx + col * (CH_BTN_W + 2), gy + row * (CH_BTN_H + 2), labels[row * 8 + col], ch);
            }
        }

        int btnY = gy + layout.length * (CH_BTN_H + 2) + 8;
        boolean none = channelIndex == SpeakerBlockEntity.CH_NONE;
        noneBtn = new BlackGoldButton(gx, btnY, 50, CH_BTN_H + 2,
                Component.literal(none ? "\u25c9\u9759\u97f3" : "\u25cb\u9759\u97f3"),
                btn -> {
                    channelIndex = SpeakerBlockEntity.CH_NONE;
                    refreshAll();
                },
                none ? GOLD : TEXT_SECONDARY);
        addRenderableWidget(noneBtn);

        jocBtn = new BlackGoldButton(gx + 58, btnY, 128, CH_BTN_H + 2,
                Component.literal(autoMixJoc ? "\u25c9\u878d\u5408\u672a\u5206\u914d\u58f0\u9053"
                        : "\u25cb\u878d\u5408\u672a\u5206\u914d\u58f0\u9053"),
                btn -> {
                    autoMixJoc = !autoMixJoc;
                    refreshAll();
                },
                autoMixJoc ? GOLD : TEXT_SECONDARY);
        addRenderableWidget(jocBtn);

        addConfigSlider(gx + 24, btnY + CH_BTN_H + 12, 0.0f, 2.0f, volume, v -> volume = v);
    }

    private void addChBtn(int x, int y, String label, int ch) {
        boolean on = channelIndex == ch;
        BlackGoldButton btn = new BlackGoldButton(x, y, CH_BTN_W, CH_BTN_H,
                Component.literal(on ? "\u25c9" + label : "\u25cb" + label),
                b -> {
                    channelIndex = ch;
                    refreshAll();
                },
                on ? GOLD : TEXT_SECONDARY);
        addRenderableWidget(btn);
        chButtons.put(ch, btn);
    }

    private void refreshAll() {
        for (var e : chButtons.entrySet()) {
            int ch = e.getKey();
            BlackGoldButton btn = e.getValue();
            boolean on = channelIndex == ch;
            String pure = btn.getMessage().getString();
            if (pure.length() > 1 && (pure.charAt(0) == '\u25c9' || pure.charAt(0) == '\u25cb'))
                pure = pure.substring(1);
            btn.setMessage(Component.literal(on ? "\u25c9" + pure : "\u25cb" + pure));
        }
        boolean none = channelIndex == SpeakerBlockEntity.CH_NONE;
        if (noneBtn != null)
            noneBtn.setMessage(Component.literal(none ? "\u25c9\u9759\u97f3" : "\u25cb\u9759\u97f3"));
        if (jocBtn != null)
            jocBtn.setMessage(Component.literal(
                    autoMixJoc ? "\u25c9\u878d\u5408\u672a\u5206\u914d\u58f0\u9053"
                            : "\u25cb\u878d\u5408\u672a\u5206\u914d\u58f0\u9053"));
    }

    private SpeakerBlockEntity getSpeakerBE() {
        if (minecraft == null || minecraft.level == null)
            return null;
        BlockEntity be = minecraft.level.getBlockEntity(blockPos);
        return be instanceof SpeakerBlockEntity s ? s : null;
    }

    @Override
    protected void onSave() {
        SpeakerBlockEntity be = getSpeakerBE();
        if (be != null && minecraft != null && minecraft.getConnection() != null) {
            minecraft.getConnection().send(new SpeakerConfigPacket(blockPos, channelIndex, volume, autoMixJoc));
            be.setChannelIndex(channelIndex);
            be.setVolume(volume);
            be.setAutoMixJoc(autoMixJoc);
            be.markDirtyAndSync();
        }
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        int cx = bx + BOX_W / 2;
        g.centeredText(font, Component.literal("\u58f0\u9053\u9009\u62e9 7.1.4"), cx, by + HEADER_H + 2,
                TEXT_SECONDARY);
        SpeakerBlockEntity be = getSpeakerBE();
        if (be != null) {
            BlockPos linked = be.getLinkedTurntablePos();
            String info = linked != null
                    ? String.format("\u5df2\u8fde\u63a5 (%d,%d,%d)", linked.getX(), linked.getY(), linked.getZ())
                    : "\u672a\u8fde\u63a5";
            g.centeredText(font, Component.literal(info), cx, by + BOX_H - 20, linked != null ? GOLD : TEXT_DIM);
        }
    }
}
