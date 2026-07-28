package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.network.LiveStreamerControlPacket;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 直播机 GUI — 直播间号输入 / 开播停止 / 音量。
 *
 * <p>
 * 直播没有时间轴，因此不提供进度条和拖动。
 * </p>
 */
public class LiveStreamerScreen extends BlackGoldScreen {
    private static final int FIELD_H = 20;

    private EditBox roomField;
    private BlackGoldButton startButton;
    private BlackGoldButton stopButton;
    private float volume = 1.0f;
    private int lastSentVolumePerMille = -1;

    public LiveStreamerScreen(BlockPos pos) {
        super(Component.translatable("gui.net_music_can_play_bili.live_streamer"), pos);
    }

    @Override
    protected int boxH() {
        return 190;
    }

    @Override
    protected void buildWidgets() {
        int bx = boxX(), by = boxY();
        LiveStreamerBlockEntity be = getStreamerBE();
        String savedRoom = roomField != null ? roomField.getValue() : null;
        volume = be != null ? be.getVolume() : volume;

        int fx = bx + PAD, fy = by + HEADER_H + 26;
        int fieldW = BOX_W - PAD * 2;
        roomField = new EditBox(font, fx, fy, fieldW, FIELD_H, Component.empty());
        roomField.setMaxLength(128);
        roomField.setHint(Component.translatable("gui.net_music_can_play_bili.live_streamer.room_hint"));
        roomField.setValue(savedRoom != null ? savedRoom : (be != null ? be.getRoomId() : ""));
        addRenderableWidget(roomField);

        int btnY = fy + FIELD_H + 12;
        int btnW = (fieldW - 8) / 2;
        startButton = new BlackGoldButton(fx, btnY, btnW, FIELD_H,
                Component.translatable("gui.net_music_can_play_bili.live_streamer.start"),
                btn -> sendControl(LiveStreamerControlPacket.Action.START), GOLD);
        addRenderableWidget(startButton);

        stopButton = new BlackGoldButton(fx + btnW + 8, btnY, btnW, FIELD_H,
                Component.translatable("gui.net_music_can_play_bili.live_streamer.stop"),
                btn -> sendControl(LiveStreamerControlPacket.Action.STOP), TEXT_SECONDARY);
        addRenderableWidget(stopButton);

        // 拖动只更新本地预览，松手一次性下发；文本框仅在用户主动输入时下发。
        // （applyValue 会回写联动文本框，若 responder 直接发包会变成拖动期间每帧一包。）
        ConfigSlider volumeSlider = new ConfigSlider(fx + 24, btnY + FIELD_H + 16, SLIDER_W, SLIDER_H,
                0.0f, 1.0f, volume, v -> applyLocalVolume(v)) {
            @Override
            public void onRelease(MouseButtonEvent event) {
                super.onRelease(event);
                sendVolume();
            }
        };
        addConfigSlider(volumeSlider, volume, v -> {
            applyLocalVolume(v);
            if (volumeSlider.linkedBox != null && volumeSlider.linkedBox.isFocused()) {
                sendVolume();
            }
        });
    }

    /** 本地即时生效：OpenAL 音量由客户端 BE 音量驱动，不必等服务端方块同步回包。 */
    private void applyLocalVolume(float value) {
        volume = value;
        LiveStreamerBlockEntity be = getStreamerBE();
        if (be != null) {
            be.setClientVolumePerMille(Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 1000.0f));
        }
    }

    private void sendVolume() {
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        int volumePerMille = Math.round(Math.max(0.0f, Math.min(1.0f, volume)) * 1000.0f);
        if (volumePerMille == lastSentVolumePerMille) {
            return;
        }
        lastSentVolumePerMille = volumePerMille;
        minecraft.getConnection().send(new LiveStreamerControlPacket(blockPos,
                LiveStreamerControlPacket.Action.SET_VOLUME, "", volumePerMille));
    }

    private void sendControl(LiveStreamerControlPacket.Action action) {
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        String roomInput = roomField != null ? roomField.getValue().trim() : "";
        minecraft.getConnection().send(new LiveStreamerControlPacket(blockPos, action, roomInput, 0));
    }

    @Override
    protected void onSave() {
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        LiveStreamerBlockEntity be = getStreamerBE();
        String roomInput = roomField != null ? roomField.getValue().trim() : "";
        if (be != null && !roomInput.equals(be.getRoomId())) {
            minecraft.getConnection().send(new LiveStreamerControlPacket(blockPos,
                    LiveStreamerControlPacket.Action.SET_ROOM, roomInput, 0));
        }
        int volumePerMille = Math.round(Math.max(0.0f, Math.min(1.0f, volume)) * 1000.0f);
        if (be == null || be.getVolumePerMille() != volumePerMille) {
            minecraft.getConnection().send(new LiveStreamerControlPacket(blockPos,
                    LiveStreamerControlPacket.Action.SET_VOLUME, "", volumePerMille));
        }
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        int cx = bx + BOX_W / 2;
        g.centeredText(font, Component.translatable("gui.net_music_can_play_bili.live_streamer.room_label"),
                cx, by + HEADER_H + 8, TEXT_SECONDARY);

        LiveStreamerBlockEntity be = getStreamerBE();
        Component status;
        int color;
        if (be != null && be.isPlaying()) {
            status = Component.translatable("gui.net_music_can_play_bili.live_streamer.status_playing",
                    be.getRoomId());
            color = GOLD;
        } else if (be != null && be.isWaitingForLive()) {
            status = Component.translatable("gui.net_music_can_play_bili.live_streamer.status_waiting",
                    be.getRoomId());
            color = 0xFFF2C94C;
        } else if (be != null && !be.getRoomId().isEmpty()) {
            status = Component.translatable("gui.net_music_can_play_bili.live_streamer.status_ready",
                    be.getRoomId());
            color = TEXT_SECONDARY;
        } else {
            status = Component.translatable("gui.net_music_can_play_bili.live_streamer.status_empty");
            color = TEXT_DIM;
        }
        g.centeredText(font, status, cx, by + boxH() - 22, color);
    }

    private LiveStreamerBlockEntity getStreamerBE() {
        if (minecraft == null || minecraft.level == null) {
            return null;
        }
        BlockEntity be = minecraft.level.getBlockEntity(blockPos);
        return be instanceof LiveStreamerBlockEntity streamer ? streamer : null;
    }
}
