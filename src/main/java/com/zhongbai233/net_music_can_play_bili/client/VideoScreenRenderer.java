package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import com.zhongbai233.net_music_can_play_bili.bili.codec.FfmpegSubprocessDecoder;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

@EventBusSubscriber(value = Dist.CLIENT)
public final class VideoScreenRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int COLS = 40;
    private static final int ROWS = 22;
    private static final int FPS = 20;
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile int videoTextureId = -1;
    private static int emptyFrameCount = 0;

    private VideoScreenRenderer() {
    }

    public static void startPlayback(String videoUrl) {
        if (!running.compareAndSet(false, true)) return;
        LOGGER.info("启动视频播放: {}×{} @ {}fps", COLS, ROWS, FPS);

        Minecraft.getInstance().execute(() -> {
            videoTextureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, videoTextureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            ByteBuffer init = ByteBuffer.allocateDirect(COLS * ROWS * 4);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                    COLS, ROWS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, init);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            LOGGER.info("GL 纹理已创建 id={}", videoTextureId);
        });

        Thread player = new Thread(() -> {
            try (FfmpegSubprocessDecoder dec = new FfmpegSubprocessDecoder(
                    videoUrl, COLS, ROWS, FPS)) {
                long frameInterval = 1000 / FPS;
                long nextFrameTime = System.currentTimeMillis();

                while (running.get()) {
                    byte[] rgba = dec.getNextFrame();
                    if (rgba == null) { LOGGER.info("视频流结束 ({}帧)", dec.getTotalFrames()); break; }

                    final byte[] frame = rgba;
                    Minecraft.getInstance().execute(() -> {
                        if (videoTextureId < 0) return;
                        ByteBuffer buf = ByteBuffer.allocateDirect(frame.length);
                        buf.put(frame);
                        buf.flip();
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, videoTextureId);
                        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0,
                                COLS, ROWS, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
                    });

                    if (dec.getTotalFrames() == 1) LOGGER.info("首帧已解码 → 纹理上传中");

                    long now = System.currentTimeMillis();
                    long sleep = nextFrameTime - now;
                    if (sleep > 0) Thread.sleep(sleep);
                    nextFrameTime += frameInterval;
                    if (nextFrameTime < now) nextFrameTime = now + frameInterval;
                }
            } catch (Exception e) {
                LOGGER.error("视频播放异常", e);
            } finally {
                running.set(false);
                Minecraft.getInstance().execute(() -> {
                    if (videoTextureId >= 0) { GL11.glDeleteTextures(videoTextureId); videoTextureId = -1; }
                });
            }
        }, "video-player");
        player.setDaemon(true);
        player.start();
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        int texId = videoTextureId;
        if (texId < 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Vec3 look = mc.player.getLookAngle();
        Vec3 pos = mc.player.getEyePosition().add(look.scale(5.0));

        PoseStack ps = event.getPoseStack();

        int oldProg = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);
        int oldTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);

        ps.pushPose();
        ps.translate(pos.x, pos.y, pos.z);
        float yr = mc.player.getYRot();
        float xr = mc.player.getXRot();
        ps.mulPose(Axis.YP.rotationDegrees(-yr + 180));
        ps.mulPose(Axis.XP.rotationDegrees(xr));

        float s = 3.0f; // 6 blocks wide
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 1); GL11.glVertex3f(-s, -s, 0);
        GL11.glTexCoord2f(0, 0); GL11.glVertex3f(-s,  s, 0);
        GL11.glTexCoord2f(1, 0); GL11.glVertex3f( s,  s, 0);
        GL11.glTexCoord2f(1, 1); GL11.glVertex3f( s, -s, 0);
        GL11.glEnd();

        ps.popPose();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTex);
        if (!blend) GL11.glDisable(GL11.GL_BLEND);
        if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
        else GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL20.glUseProgram(oldProg);
    }

    public static void stopPlayback() { running.set(false); }
}
