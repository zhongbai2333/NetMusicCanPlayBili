package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.system.MemoryUtil;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker.Category;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

/** NV12 interleaved UV plane with truthful RG8 storage and portable RGBA8 fallback. */
final class Nv12UvTexture extends AbstractTexture {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean RG8_ENABLED = VideoPipelineProperties.upload().nv12UvRg8Enabled();
    private final String label;
    private int width;
    private int height;
    private ByteBuffer uploadBuffer;
    private boolean rg8Texture;

    Nv12UvTexture(String label, int width, int height) {
        this.label = label;
        recreate(width, height);
    }

    boolean matches(int width, int height) {
        return this.width == width && this.height == height && texture != null && !texture.isClosed();
    }

    void recreate(int width, int height) {
        VideoPlanePacking.byteCount(width, height, 4);
        close();
        this.width = width;
        this.height = height;
        int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
        rg8Texture = false;
        if (RG8_ENABLED) {
            try {
                texture = RenderSystem.getDevice().createTexture(label, usage,
                        GpuFormat.RG8_UNORM, width, height, 1, 1);
                rg8Texture = true;
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("NV12: RG8_UNORM unavailable; using RGBA8_UNORM UV storage: {}", e.toString());
            }
        }
        try {
            if (texture == null) {
                texture = RenderSystem.getDevice().createTexture(label, usage,
                        GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            }
            sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
            textureView = RenderSystem.getDevice().createTextureView(texture);
            uploadBuffer = MemoryUtil.memAlloc(VideoPlanePacking.byteCount(width, height, rg8Texture ? 2 : 4));
            MemoryResourceTracker.allocated(Category.TEXTURE_STAGING, uploadBuffer.capacity());
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
        int pixelCount = VideoPlanePacking.byteCount(width, height, 1);
        if (rg8Texture) {
            VideoPlanePacking.copy(uploadBuffer, pixels, offset, Math.multiplyExact(pixelCount, 2));
        } else {
            VideoPlanePacking.expandUv(uploadBuffer, pixels, offset, pixelCount);
        }
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
