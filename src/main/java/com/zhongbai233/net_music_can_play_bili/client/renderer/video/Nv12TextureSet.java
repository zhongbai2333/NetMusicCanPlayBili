package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;

/**
 * NV12 双平面纹理集：Y=RED8，UV=RG8（旧 GPU/驱动不可用时由 {@link Nv12UvTexture} 回退）。
 */
public final class Nv12TextureSet implements VideoYuvTextureSet {
    private static final boolean PBO_UPLOAD = Boolean.parseBoolean(
            System.getProperty("ncpb.video.nv12.pbo", "true"));
    private final Identifier yId;
    private final Identifier uvId;
    private final Identifier placeholderId;
    private final String labelPrefix;
    private Yuv420pPlaneTexture yTexture;
    private Nv12UvTexture uvTexture;
    private int width;
    private int height;

    public Nv12TextureSet(Identifier yId, Identifier uvId, Identifier placeholderId, String labelPrefix) {
        this.yId = yId;
        this.uvId = uvId;
        this.placeholderId = placeholderId;
        this.labelPrefix = labelPrefix;
    }

    public Identifier yId() {
        return yId;
    }

    public Identifier uId() {
        return uvId;
    }

    public Identifier vId() {
        return placeholderId;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public Fmp4NativeVideoDecoder.DecodedFrame.Format format() {
        return Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
    }

    public boolean upload(byte[] nv12, int width, int height) {
        int ySize = width * height;
        int uvWidth = Math.max(1, width / 2);
        int uvHeight = Math.max(1, height / 2);
        int uvSize = uvWidth * uvHeight * 2;
        if (nv12 == null || nv12.length < ySize + uvSize) {
            return false;
        }
        ensureTextures(width, height, uvWidth, uvHeight);
        if (PBO_UPLOAD) {
            boolean yOk = yTexture.uploadPbo(nv12, 0);
            boolean uvOk = uvTexture.uploadPbo(nv12, ySize);
            if (yOk && uvOk) {
                return true;
            }
        }
        yTexture.upload(nv12, 0);
        uvTexture.upload(nv12, ySize);
        return true;
    }

    public boolean upload(ByteBuffer nv12, int byteLength, int width, int height) {
        int ySize = width * height;
        int uvWidth = Math.max(1, width / 2);
        int uvHeight = Math.max(1, height / 2);
        int uvSize = uvWidth * uvHeight * 2;
        if (nv12 == null || byteLength < ySize + uvSize || nv12.limit() < ySize + uvSize) {
            return false;
        }
        ensureTextures(width, height, uvWidth, uvHeight);
        if (PBO_UPLOAD) {
            boolean yOk = yTexture.uploadPbo(nv12, 0);
            boolean uvOk = uvTexture.uploadPbo(nv12, ySize);
            if (yOk && uvOk) {
                return true;
            }
        }
        return upload(frameBytes(nv12, ySize + uvSize), width, height);
    }

    private static byte[] frameBytes(ByteBuffer buffer, int byteCount) {
        ByteBuffer src = buffer.duplicate();
        src.position(0);
        src.limit(Math.min(src.limit(), byteCount));
        byte[] out = new byte[src.remaining()];
        src.get(out);
        return out;
    }

    private void ensureTextures(int width, int height, int uvWidth, int uvHeight) {
        if (yTexture != null && yTexture.matches(width, height)
                && uvTexture != null && uvTexture.matches(uvWidth, uvHeight)) {
            this.width = width;
            this.height = height;
            return;
        }
        close();
        this.width = width;
        this.height = height;
        try {
            yTexture = new Yuv420pPlaneTexture(labelPrefix + "_y", width, height);
            uvTexture = new Nv12UvTexture(labelPrefix + "_uv", uvWidth, uvHeight);
            Minecraft.getInstance().getTextureManager().register(yId, yTexture);
            Minecraft.getInstance().getTextureManager().register(uvId, uvTexture);
        } catch (RuntimeException | LinkageError e) {
            close();
            throw e;
        }
    }

    @Override
    public void close() {
        if (yTexture != null) {
            Minecraft.getInstance().getTextureManager().release(yId);
            yTexture.close();
            yTexture = null;
        }
        if (uvTexture != null) {
            Minecraft.getInstance().getTextureManager().release(uvId);
            uvTexture.close();
            uvTexture = null;
        }
    }
}