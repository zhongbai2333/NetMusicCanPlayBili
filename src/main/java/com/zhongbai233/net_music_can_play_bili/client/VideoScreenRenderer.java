package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import com.zhongbai233.net_music_can_play_bili.bili.codec.FfmpegSubprocessDecoder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 视频画面世界内渲染器 — 用彩色 █ 文字模拟，走 MC 文字渲染管线。
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VideoScreenRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int COLS = 40;
    private static final int ROWS = 22;
    private static final int FPS = 20;
    private static final float TEXT_SCALE = 0.025F;
    private static final float CHAR_STEP = 10.0F;
    private static final AtomicReference<byte[]> currentFrame = new AtomicReference<>();
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private VideoScreenRenderer() {
    }

    public static void startPlayback(String videoUrl) {
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("视频已在播放中");
            return;
        }
        LOGGER.info("启动视频播放: {}×{} @ {}fps", COLS, ROWS, FPS);

        Thread player = new Thread(() -> {
            try (FfmpegSubprocessDecoder dec = new FfmpegSubprocessDecoder(
                    videoUrl, COLS, ROWS, FPS)) {
                long frameInterval = 1000 / FPS;
                long nextFrameTime = System.currentTimeMillis();

                while (running.get()) {
                    byte[] rgba = dec.getNextFrame();
                    if (rgba == null) {
                        LOGGER.info("视频流结束 ({} 帧)", dec.getTotalFrames());
                        break;
                    }
                    currentFrame.set(rgba);

                    long now = System.currentTimeMillis();
                    long sleep = nextFrameTime - now;
                    if (sleep > 0) {
                        try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
                    }
                    nextFrameTime += frameInterval;
                    if (nextFrameTime < now) nextFrameTime = now + frameInterval;
                }
            } catch (IOException e) {
                LOGGER.error("视频播放异常", e);
            } finally {
                running.set(false);
                currentFrame.set(null);
            }
        }, "video-player");
        player.setDaemon(true);
        player.start();
    }

    public static void stopPlayback() {
        running.set(false);
        currentFrame.set(null);
    }

    // ── 渲染 ──

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        byte[] rgba = currentFrame.get();
        if (rgba == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.font == null) return;

        // 玩家前方 5 格
        Vec3 look = mc.player.getLookAngle();
        Vec3 pos = mc.player.getEyePosition().add(look.scale(5.0)).add(0, 1.5, 0);

        PoseStack poseStack = event.getPoseStack();
        Font font = mc.font;
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(pos.x, pos.y, pos.z);
        // 面朝相机
        float yRot = mc.player.getYRot();
        float xRot = mc.player.getXRot();
        poseStack.mulPose(Axis.YP.rotationDegrees(-yRot));
        poseStack.mulPose(Axis.XP.rotationDegrees(xRot));
        poseStack.scale(-TEXT_SCALE, -TEXT_SCALE, -TEXT_SCALE);

        float totalW = COLS * CHAR_STEP;
        float totalH = ROWS * CHAR_STEP;
        float startX = -totalW / 2.0F + CHAR_STEP / 2.0F;
        float startY = totalH / 2.0F - CHAR_STEP / 2.0F;
        int light = 0x00F000F0; // fullbright

        for (int row = 0; row < ROWS; row++) {
            int rowStart = row * COLS * 4;
            float y = startY - row * CHAR_STEP;

            // Run-length 编码
            int segStart = 0;
            int prevColor = 0;
            boolean hasPrev = false;

            for (int col = 0; col <= COLS; col++) {
                int color = 0;
                if (col < COLS) {
                    int i = rowStart + col * 4;
                    color = ((rgba[i] & 0xFF) << 16) | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF);
                }

                if (hasPrev && (col == COLS || color != prevColor)) {
                    // 渲染这一段
                    int segLen = col - segStart;
                    if (segLen > 0) {
                        StringBuilder sb = new StringBuilder(segLen);
                        for (int k = 0; k < segLen; k++) sb.append('\u2588');
                        float x = startX + segStart * CHAR_STEP;

                        font.drawInBatch(
                                Component.literal(sb.toString()),
                                x, y, prevColor | 0xFF000000,
                                false, poseStack.last().pose(), bufferSource,
                                Font.DisplayMode.NORMAL,
                                0x00000000, light);
                    }
                    segStart = col;
                }

                prevColor = color;
                hasPrev = true;
            }
        }

        poseStack.popPose();
        bufferSource.endBatch();
    }
}
