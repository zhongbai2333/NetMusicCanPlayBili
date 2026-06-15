package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * 单个 8 位 Y/U/V 平面，底层使用 RED8 GPU 纹理。
 */
final class Yuv420pPlaneTexture extends AbstractTexture {
    private final String label;
    private int width;
    private int height;
    private ByteBuffer uploadBuffer;
    private Nv12PboUploader pboUploader;

    Yuv420pPlaneTexture(String label, int width, int height) {
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
        this.texture = RenderSystem.getDevice().createTexture(label,
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                TextureFormat.RED8, this.width, this.height, 1, 1);
        try {
            this.sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
            this.textureView = RenderSystem.getDevice().createTextureView(this.texture);
            this.uploadBuffer = MemoryUtil.memAlloc(this.width * this.height);
        } catch (RuntimeException | LinkageError e) {
            close();
            throw e;
        }
    }

    void upload(byte[] yuv420p, int offset) {
        if (texture == null || texture.isClosed()) {
            recreate(width, height);
        }
        int byteCount = width * height;
        if (uploadBuffer == null || uploadBuffer.capacity() < byteCount) {
            if (uploadBuffer != null) {
                MemoryUtil.memFree(uploadBuffer);
            }
            uploadBuffer = MemoryUtil.memAlloc(byteCount);
        }
        uploadBuffer.clear();
        uploadBuffer.put(yuv420p, offset, byteCount);
        uploadBuffer.flip();
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture,
                uploadBuffer, NativeImage.Format.LUMINANCE,
                0, 0, 0, 0, width, height);
    }

    boolean uploadPbo(byte[] data, int offset) {
        if (texture == null || texture.isClosed()) {
            recreate(width, height);
        }
        if (pboUploader == null) {
            pboUploader = new Nv12PboUploader(label + "_pbo");
        }
        return pboUploader.uploadRed8(texture, data, offset, width, height);
    }

    boolean uploadPbo(ByteBuffer data, int offset) {
        if (texture == null || texture.isClosed()) {
            recreate(width, height);
        }
        if (pboUploader == null) {
            pboUploader = new Nv12PboUploader(label + "_pbo");
        }
        return pboUploader.uploadRed8(texture, data, offset, width, height);
    }

    @Override
    public void close() {
        if (pboUploader != null) {
            pboUploader.close();
            pboUploader = null;
        }
        if (uploadBuffer != null) {
            MemoryUtil.memFree(uploadBuffer);
            uploadBuffer = null;
        }
        super.close();
    }
}