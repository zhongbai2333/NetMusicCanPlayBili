package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.system.MemoryUtil;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker.Category;

import java.nio.ByteBuffer;

/** One R8 plane uploaded through Renderpearl on both OpenGL and Vulkan. */
final class Yuv420pPlaneTexture extends AbstractTexture {
    private final String label;
    private int width;
    private int height;
    private ByteBuffer uploadBuffer;

    Yuv420pPlaneTexture(String label, int width, int height) {
        this.label = label;
        recreate(width, height);
    }

    boolean matches(int width, int height) {
        return this.width == width && this.height == height && texture != null && !texture.isClosed();
    }

    void recreate(int width, int height) {
        int bytes = VideoPlanePacking.byteCount(width, height, 1);
        close();
        this.width = width;
        this.height = height;
        try {
            texture = RenderSystem.getDevice().createTexture(label,
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.R8_UNORM, width, height, 1, 1);
            sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
            textureView = RenderSystem.getDevice().createTextureView(texture);
            uploadBuffer = MemoryUtil.memAlloc(bytes);
            MemoryResourceTracker.allocated(Category.TEXTURE_STAGING, bytes);
        } catch (RuntimeException | LinkageError e) {
            close();
            throw e;
        }
    }

    void upload(byte[] pixels, int offset) {
        upload(ByteBuffer.wrap(pixels), offset);
    }

    void upload(ByteBuffer pixels, int offset) {
        if (texture == null || texture.isClosed()) recreate(width, height);
        VideoPlanePacking.copy(uploadBuffer, pixels, offset, VideoPlanePacking.byteCount(width, height, 1));
        // Renderpearl copies CPU bytes into backend-owned transient staging before returning.
        // Do not submit here: the game owns the encoder's frame/submission boundary.
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, uploadBuffer,
                0, 0, 0, 0, width, height);
    }

    @Override
    public void close() {
        if (uploadBuffer != null) {
            MemoryResourceTracker.freed(Category.TEXTURE_STAGING, uploadBuffer.capacity());
            MemoryUtil.memFree(uploadBuffer);
            uploadBuffer = null;
        }
        super.close();
    }
}
