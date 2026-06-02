package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.codec.FfmpegSubprocessDecoder;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
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
    private static int texId = -1, vao, prog;
    private static boolean glOk;

    private VideoScreenRenderer() {}

    public static void startPlayback(String videoUrl) {
        if (!running.compareAndSet(false, true)) return;
        LOGGER.info("GUI视频: {}x{}", COLS, ROWS);

        Minecraft.getInstance().execute(() -> {
            initGL();
            texId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            ByteBuffer init = ByteBuffer.allocateDirect(COLS * ROWS * 4);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, COLS, ROWS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, init);
            LOGGER.info("GUI纹理 id={}", texId);
        });

        new Thread(() -> {
            try (var dec = new FfmpegSubprocessDecoder(videoUrl, COLS, ROWS, FPS)) {
                long fi = 1000 / FPS, nft = System.currentTimeMillis();
                while (running.get()) {
                    byte[] rgba = dec.getNextFrame();
                    if (rgba == null) { LOGGER.info("视频结束"); break; }
                    final byte[] f = rgba;
                    Minecraft.getInstance().execute(() -> upload(f));
                    long s = nft - System.currentTimeMillis();
                    if (s > 0) Thread.sleep(s);
                    nft += fi;
                }
            } catch (Exception e) { LOGGER.error("异常", e); }
            finally { running.set(false); }
        }, "video-player").start();
    }

    private static void initGL() {
        if (glOk) return;
        float[] v = {-1,-1,0,1, -1,1,0,0, 1,1,1,0, 1,-1,1,1};
        vao = GL30.glGenVertexArrays(); GL30.glBindVertexArray(vao);
        int vbo = GL15.glGenBuffers(); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, v, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0); GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8); GL20.glEnableVertexAttribArray(1);
        GL30.glBindVertexArray(0);
        String vs = "#version 330\nlayout(location=0)in vec2 p;layout(location=1)in vec2 t;out vec2 uv;uniform mat4 m;void main(){gl_Position=m*vec4(p,0,1);uv=t;}";
        String fs = "#version 330\nin vec2 uv;out vec4 c;uniform sampler2D s;void main(){c=texture(s,uv);}";
        prog = GL20.glCreateProgram();
        int vsh = GL20.glCreateShader(GL20.GL_VERTEX_SHADER); GL20.glShaderSource(vsh, vs); GL20.glCompileShader(vsh);
        int fsh = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER); GL20.glShaderSource(fsh, fs); GL20.glCompileShader(fsh);
        GL20.glAttachShader(prog, vsh); GL20.glAttachShader(prog, fsh); GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vsh); GL20.glDeleteShader(fsh);
        glOk = true;
        LOGGER.info("GL OK: VAO={} Prog={}", vao, prog);
    }

    private static void upload(byte[] rgba) {
        if (texId < 0) return;
        ByteBuffer b = ByteBuffer.allocateDirect(rgba.length); b.put(rgba); b.flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, COLS, ROWS, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, b);
    }

    /** CPU 侧构建正交投影矩阵: 屏幕坐标 → NDC */
    private static FloatBuffer ortho(float l, float r, float b, float t) {
        FloatBuffer m = org.lwjgl.BufferUtils.createFloatBuffer(16);
        m.put(0, 2/(r-l)); m.put(5, 2/(t-b)); m.put(10, -1);
        m.put(12, -(r+l)/(r-l)); m.put(13, -(t+b)/(t-b)); m.put(14, 0); m.put(15, 1);
        return m;
    }

    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Post event) {
        if (!glOk || texId < 0) return;
        var w = Minecraft.getInstance().getWindow();
        float sw = w.getGuiScaledWidth(), sh = w.getGuiScaledHeight();

        int oldProg = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldVAO = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL20.glUseProgram(prog);
        GL30.glBindVertexArray(vao);

        int vw = 320, vh = 176, vx = (int)sw - vw - 10, vy = (int)sh - vh - 10;
        int loc = GL20.glGetUniformLocation(prog, "m");
        FloatBuffer mat;

        // 半透明背景
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL20.glUniform4f(GL20.glGetUniformLocation(prog, "s"), 0, 0, 0, 0.5f); // unused but sets color via a different path... 

        // 直接用 Blaze3D 的方式：用不同的 shader... 太复杂
        // 简化：就用视频纹理画到整个 320x176 区域，让黑色像素自然出现
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        mat = ortho(vx, vx+vw, vy+vh, vy);
        GL20.glUniformMatrix4fv(loc, false, mat);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);

        GL30.glBindVertexArray(oldVAO);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTex);
        GL20.glUseProgram(oldProg);
        if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (!blend) GL11.glDisable(GL11.GL_BLEND);
    }
}
