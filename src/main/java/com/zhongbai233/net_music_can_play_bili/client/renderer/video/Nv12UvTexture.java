package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.system.MemoryUtil;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker.Category;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

/**
 * NV12 UV 平面纹理。
 *
 * <p>
 * Minecraft 26.2 使用后端无关的 {@link GpuFormat#RG8_UNORM}；
 * 若创建失败，再回退 RGBA8 承载 interleaved UV：R=U, G=V, B=0, A=255。
 * </p>
 */
final class Nv12UvTexture extends AbstractTexture {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean RG8_ENABLED = VideoPipelineProperties.upload().nv12UvRg8Enabled();

    private final String label;
    private int width;
    private int height;
    private ByteBuffer uploadBuffer;
    private Nv12PboUploader pboUploader;
    private boolean rg8Texture;

    Nv12UvTexture(String label, int width, int height) {
        this.label = label;
        recreate(width, height);
    }

    boolean matches(int width, int height) {
        return this.width == width && this.height == height && this.texture != null && !this.texture.isClosed();
    }

    void recreate(int width, int height) {
        close();
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
        this.rg8Texture = false;
        if (RG8_ENABLED) {
            try {
                this.texture = RenderSystem.getDevice().createTexture(label, usage, GpuFormat.RG8_UNORM,
                        this.width, this.height, 1, 1);
                this.rg8Texture = true;
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("NV12: 创建 RG8 UV 纹理失败，回退 RGBA8: {}", e.toString());
            }
        }
        if (this.texture == null) {
            this.texture = RenderSystem.getDevice().createTexture(label,
                    usage, GpuFormat.RGBA8_UNORM, this.width, this.height, 1, 1);
        }
        try {
            this.sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
            this.textureView = RenderSystem.getDevice().createTextureView(this.texture);
            this.uploadBuffer = MemoryUtil.memAlloc(this.width * this.height * (rg8Texture ? 2 : 4));
            MemoryResourceTracker.allocated(Category.TEXTURE_STAGING, this.uploadBuffer.capacity());
        } catch (RuntimeException | LinkageError e) {
            close();
            throw e;
        }
    }

    void upload(byte[] nv12, int offset) {
        if (texture == null || texture.isClosed()) {
            recreate(width, height);
        }
        int pixelCount = width * height;
        int byteCount = pixelCount * (rg8Texture ? 2 : 4);
        if (uploadBuffer == null || uploadBuffer.capacity() < byteCount) {
            if (uploadBuffer != null) {
                MemoryResourceTracker.freed(Category.TEXTURE_STAGING, uploadBuffer.capacity());
                MemoryUtil.memFree(uploadBuffer);
            }
            uploadBuffer = MemoryUtil.memAlloc(byteCount);
            MemoryResourceTracker.allocated(Category.TEXTURE_STAGING, uploadBuffer.capacity());
        }
        uploadBuffer.clear();
        if (rg8Texture) {
            uploadBuffer.put(nv12, offset, byteCount);
        } else {
            int src = offset;
            for (int i = 0; i < pixelCount; i++) {
                uploadBuffer.put(nv12[src++]);
                uploadBuffer.put(nv12[src++]);
                uploadBuffer.put((byte) 0);
                uploadBuffer.put((byte) 255);
            }
        }
        uploadBuffer.flip();
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture,
                uploadBuffer,
                0, 0, 0, 0, width, height);
    }

    /** Copies into owned staging memory before the decoder may recycle its direct frame. */
    void upload(ByteBuffer nv12, int offset) {
        if (texture == null || texture.isClosed()) {
            recreate(width, height);
        }
        int pixelCount = Math.multiplyExact(width, height);
        uploadBuffer.clear();
        if (rg8Texture) {
            uploadBuffer.put(nv12.slice(offset, Math.multiplyExact(pixelCount, 2)));
        } else {
            Nv12UploadBuffers.expandUv(nv12, offset, pixelCount, uploadBuffer);
        }
        uploadBuffer.flip();
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture,
                uploadBuffer, 0, 0, 0, 0, width, height);
    }

    boolean uploadPbo(byte[] nv12, int offset) {
        if (texture == null || texture.isClosed()) {
            recreate(width, height);
        }
        if (!VideoUploadBackend.current().supportsPbo()) {
            return false;
        }
        if (pboUploader == null) {
            pboUploader = new Nv12PboUploader(label + "_pbo");
        }
        return rg8Texture
                ? pboUploader.uploadNv12UvAsRg8(texture, nv12, offset, width, height)
                : pboUploader.uploadNv12UvAsRgba8(texture, nv12, offset, width, height);
    }

    boolean uploadPbo(ByteBuffer nv12, int offset) {
        if (texture == null || texture.isClosed()) {
            recreate(width, height);
        }
        if (!rg8Texture) {
            return false;
        }
        if (!VideoUploadBackend.current().supportsPbo()) {
            return false;
        }
        if (pboUploader == null) {
            pboUploader = new Nv12PboUploader(label + "_pbo");
        }
        return pboUploader.uploadNv12UvAsRg8(texture, nv12, offset, width, height);
    }

    @Override
    public void close() {
        if (pboUploader != null) {
            pboUploader.close();
            pboUploader = null;
        }
        if (uploadBuffer != null) {
            MemoryResourceTracker.freed(Category.TEXTURE_STAGING, uploadBuffer.capacity());
            MemoryUtil.memFree(uploadBuffer);
            uploadBuffer = null;
        }
        super.close();
    }
}
