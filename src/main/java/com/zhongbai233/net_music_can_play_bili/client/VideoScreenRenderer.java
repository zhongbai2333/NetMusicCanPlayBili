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
import org.lwjgl.opengl.*;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

@EventBusSubscriber(value = Dist.CLIENT)
public final class VideoScreenRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int COLS = 40, ROWS = 22, FPS = 20;
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile int videoTextureId = -1;
    private static int vao, vbo, shaderProgram;
    private static boolean glInitDone;

    private VideoScreenRenderer() {}

    // ── 播放控制 ──

    public static void startPlayback(String videoUrl) {
        if (!running.compareAndSet(false, true)) return;
        LOGGER.info("启动视频播放: {}x{} @ {}fps", COLS, ROWS, FPS);

        Minecraft.getInstance().execute(() -> {
            initGL();
            videoTextureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, videoTextureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            ByteBuffer init = ByteBuffer.allocateDirect(COLS * ROWS * 4);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                    COLS, ROWS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, init);
            LOGGER.info("GL纹理创建 id={}", videoTextureId);
        });

        Thread player = new Thread(() -> {
            try (FfmpegSubprocessDecoder dec = new FfmpegSubprocessDecoder(videoUrl, COLS, ROWS, FPS)) {
                long fi = 1000 / FPS, nft = System.currentTimeMillis();
                while (running.get()) {
                    byte[] rgba = dec.getNextFrame();
                    if (rgba == null) { LOGGER.info("视频流结束 ({}帧)", dec.getTotalFrames()); break; }
                    final byte[] f = rgba;
                    Minecraft.getInstance().execute(() -> uploadTexture(f));
                    if (dec.getTotalFrames() == 1) LOGGER.info("首帧已解码");
                    long s = nft - System.currentTimeMillis();
                    if (s > 0) Thread.sleep(s);
                    nft += fi;
                }
            } catch (Exception e) { LOGGER.error("视频播放异常", e); }
            finally { running.set(false); }
        }, "video-player");
        player.setDaemon(true);
        player.start();
    }

    // ── GL 初始化 (一次性) ──

    private static void initGL() {
        if (glInitDone) return;
        glInitDone = true;

        // 全屏四边形顶点: pos.xy + tex.uv
        float[] verts = {
            -1, -1,  0, 1,
            -1,  1,  0, 0,
             1,  1,  1, 0,
             1, -1,  1, 1,
        };

        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, verts, GL15.GL_STATIC_DRAW);

        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);
        GL20.glEnableVertexAttribArray(1);

        GL30.glBindVertexArray(0);

        // 极简 shader
        String vs = "#version 330 core\nlayout(location=0) in vec2 p; layout(location=1) in vec2 t; out vec2 uv; uniform mat4 mvp; void main(){gl_Position=mvp*vec4(p,0,1); uv=t;}";
        String fs = "#version 330 core\nin vec2 uv; out vec4 c; uniform sampler2D tex; void main(){c=texture(tex,uv);}";

        int v = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(v, vs);
        GL20.glCompileShader(v);

        int f = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(f, fs);
        GL20.glCompileShader(f);

        shaderProgram = GL20.glCreateProgram();
        GL20.glAttachShader(shaderProgram, v);
        GL20.glAttachShader(shaderProgram, f);
        GL20.glLinkProgram(shaderProgram);
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);

        LOGGER.info("GL初始化完成: VAO={} Shader={}", vao, shaderProgram);
    }

    private static void uploadTexture(byte[] rgba) {
        if (videoTextureId < 0) return;
        ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length);
        buf.put(rgba); buf.flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, videoTextureId);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0,
                COLS, ROWS, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
    }

    // ── 渲染 ──

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        int texId = videoTextureId;
        if (texId < 0 || !glInitDone) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Vec3 look = mc.player.getLookAngle();
        Vec3 pos = mc.player.getEyePosition().add(look.scale(5.0));

        PoseStack ps = event.getPoseStack();

        // 保存状态
        int oldProg = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int oldVAO = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        GL20.glUseProgram(shaderProgram);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL30.glBindVertexArray(vao);

        ps.pushPose();
        ps.translate(pos.x, pos.y, pos.z);
        float yr = mc.player.getYRot(), xr = mc.player.getXRot();
        ps.mulPose(Axis.YP.rotationDegrees(-yr + 180));
        ps.mulPose(Axis.XP.rotationDegrees(xr));
        ps.scale(3.0f, 3.0f, 1.0f);

        // 传 MVP 矩阵
        int mvpLoc = GL20.glGetUniformLocation(shaderProgram, "mvp");
        FloatBuffer mvpBuf = org.lwjgl.BufferUtils.createFloatBuffer(16);
        ps.last().pose().get(mvpBuf);
        GL20.glUniformMatrix4fv(mvpLoc, false, mvpBuf);

        GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);

        ps.popPose();

        // 恢复
        GL30.glBindVertexArray(oldVAO);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTex);
        GL20.glUseProgram(oldProg);
        if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
        else GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (!blend) GL11.glDisable(GL11.GL_BLEND);
    }
}
