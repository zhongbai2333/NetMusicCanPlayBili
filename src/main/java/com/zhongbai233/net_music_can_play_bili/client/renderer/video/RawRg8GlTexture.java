package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * Minecraft 26.1 的 TextureFormat 暂无 RG8；这里用裸 OpenGL 创建 GL_RG8，再包装为 GlTexture。
 */
final class RawRg8GlTexture extends GlTexture {
    private static final int GL_TEXTURE_MAX_LEVEL = 0x813D;
    private static final int GL_TEXTURE_BASE_LEVEL = 0x813C;

    private RawRg8GlTexture(int usage, String label, int width, int height, int id) {
        // TextureFormat 只用于 Minecraft 侧元数据；真实 GL storage 是下面 create() 中的 GL_RG8。
        super(usage, label, TextureFormat.RGBA8, width, height, 1, 1, id);
    }

    static RawRg8GlTexture create(String label, int usage, int width, int height) {
        int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int previousUnpackAlignment = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        int id = GL11C.glGenTextures();
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RG8,
                    Math.max(1, width), Math.max(1, height), 0,
                    GL30C.GL_RG, GL11C.GL_UNSIGNED_BYTE, 0L);
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR) {
                GL11C.glDeleteTextures(id);
                throw new IllegalStateException("创建 GL_RG8 NV12 UV 纹理失败，glError=" + error);
            }
            return new RawRg8GlTexture(usage, label, Math.max(1, width), Math.max(1, height), id);
        } finally {
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, previousUnpackAlignment);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
        }
    }
}