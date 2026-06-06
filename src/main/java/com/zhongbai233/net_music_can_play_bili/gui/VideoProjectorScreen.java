package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.network.VideoProjectorConfigPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public class VideoProjectorScreen extends BlackGoldScreen {
    private static final int[] QUALITY_VALUES = { 127, 120, 116, 112, 80, 64, 32, 16 };
    private static final List<String> QUALITY_LABELS = List.of(
            "画质：尝试最高", "尝试 4K", "尝试 1080P60", "尝试 1080P+",
            "尝试 1080P", "尝试 720P", "尝试 480P", "尝试 360P");

    private CycleWidget qualityBtn;

    public VideoProjectorScreen(BlockPos pos) {
        super(Component.literal("▣ 视频投影仪"), pos);
    }

    @Override
    protected int boxH() {
        return 318;
    }

    @Override
    protected void buildWidgets() {
        int bx = boxX(), by = boxY();
        int sliderX = bx + PAD + 54;
        int resetX = sliderX + SLIDER_W + 4 + VAL_W + 3;
        int rowY = by + HEADER_H + 54;
        VideoProjectorBlockEntity be = getProjectorBE();

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
                be != null ? be.getProjectionHeight() : 1.8f,
                v -> {
                    if (be != null)
                        be.setProjectionHeight(v);
                });
        addResetButton(resetX, rowY, 1.8f, heightS);
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
        rowY += 30;

        int qualityIndex = qualityIndex(be != null ? be.getPreferredQuality()
                : VideoProjectorBlockEntity.DEFAULT_PREFERRED_QUALITY);
        qualityBtn = addCycleWidget(sliderX, rowY, QUALITY_LABELS, qualityIndex,
                v -> {
                    if (be != null) {
                        be.setPreferredQuality(QUALITY_VALUES[Math.clamp(v, 0, QUALITY_VALUES.length - 1)]);
                        sendConfigToServer(be);
                        ModernTurntableVideoClient.refreshProjector(blockPos);
                    }
                });
    }

    @Override
    protected void onSave() {
        VideoProjectorBlockEntity be = getProjectorBE();
        if (be != null) {
            sendConfigToServer(be);
        }
    }

    private void sendConfigToServer(VideoProjectorBlockEntity be) {
        if (minecraft != null && minecraft.getConnection() != null) {
            minecraft.getConnection().send(new VideoProjectorConfigPacket(
                    blockPos,
                    be.getProjectionYaw(), be.getProjectionPitch(), be.getProjectionScale(),
                    be.getProjectionHeight(), be.getProjectionDistanceX(), be.getProjectionDistanceZ(),
                    be.getPreferredQuality()));
        }
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        drawLinkInfo(g, bx, by);
        drawLabels(g, bx, by);
        drawQualityOverlay(g);
    }

    private VideoProjectorBlockEntity getProjectorBE() {
        if (minecraft == null || minecraft.level == null)
            return null;
        BlockEntity be = minecraft.level.getBlockEntity(blockPos);
        return be instanceof VideoProjectorBlockEntity p ? p : null;
    }

    private void drawLinkInfo(GuiGraphicsExtractor g, int bx, int by) {
        int cx = bx + BOX_W / 2, iy = by + HEADER_H + 10;
        var level = Minecraft.getInstance().level;
        if (level == null)
            return;
        BlockEntity be = level.getBlockEntity(blockPos);
        if (!(be instanceof VideoProjectorBlockEntity p))
            return;
        BlockPos linked = p.getLinkedTurntablePos();
        if (linked == null) {
            g.centeredText(font, Component.literal("未连接"), cx, iy, TEXT_DIM);
            return;
        }
        String info = String.format("已连接 (%d,%d,%d)",
                linked.getX(), linked.getY(), linked.getZ());
        if (level.getBlockEntity(linked) instanceof ModernTurntableBlockEntity t
                && !t.getSongName().isBlank()) {
            String s = t.getSongName();
            info = s.length() > 26 ? s.substring(0, 24) + ".." : s;
        }
        g.centeredText(font, Component.literal(info), cx, iy, GOLD);

        VideoBillboardPreview.VideoStatus status = VideoBillboardPreview.getStatusForProjector(blockPos);
        String playbackInfo;
        if (status.active()) {
            playbackInfo = String.format("当前播放：%dx%d @ %dfps%s",
                    status.width(), status.height(), status.fps(), status.synced() ? " · 同步中" : "");
        } else {
            playbackInfo = "当前播放：无";
        }
        g.centeredText(font, Component.literal(playbackInfo), cx, iy + 12, TEXT_DIM);
    }

    private void drawLabels(GuiGraphicsExtractor g, int bx, int by) {
        int lx = bx + PAD, ry = by + HEADER_H + 58;
        String[] labels = { "水平朝向", "俯仰角度", "投影高度", "投影X轴", "投影Z轴", "画面大小", "请求画质" };
        for (String lb : labels) {
            g.centeredText(font, Component.literal(lb), lx + 27, ry, TEXT_SECONDARY);
            ry += 26;
        }
        g.centeredText(font, Component.literal("实际清晰度受登录/大会员限制，会自动降到可用最高"),
                bx + BOX_W / 2, by + boxH() - 28, TEXT_DIM);
    }

    private void drawQualityOverlay(GuiGraphicsExtractor g) {
        if (qualityBtn == null)
            return;
        int x = qualityBtn.getX(), y = qualityBtn.getY(), w = qualityBtn.getWidth(), h = qualityBtn.getHeight();
        g.fillGradient(x, y, x + w, y + h, GOLD & 0x30FFFFFF, GOLD & 0x18FFFFFF);
        g.centeredText(font, Component.literal("◈ " + qualityBtn.currentOption()),
                x + w / 2, y + 5, GOLD);
    }

    private static int qualityIndex(int quality) {
        for (int i = 0; i < QUALITY_VALUES.length; i++) {
            if (QUALITY_VALUES[i] == quality) {
                return i;
            }
        }
        return 0;
    }
}