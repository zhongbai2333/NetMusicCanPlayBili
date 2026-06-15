package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;

/**
 * 三张已注册的 RED8 纹理，用于承载一帧 packed YUV420P/I420 数据。
 */
final class Yuv420pTextureSet implements VideoYuvTextureSet {
    private final Identifier yId;
    private final Identifier uId;
    private final Identifier vId;
    private final String labelPrefix;
    private Yuv420pPlaneTexture yTexture;
    private Yuv420pPlaneTexture uTexture;
    private Yuv420pPlaneTexture vTexture;
    private int width;
    private int height;

    Yuv420pTextureSet(Identifier yId, Identifier uId, Identifier vId, String labelPrefix) {
        this.yId = yId;
        this.uId = uId;
        this.vId = vId;
        this.labelPrefix = labelPrefix;
    }

    public Identifier yId() {
        return yId;
    }

    public Identifier uId() {
        return uId;
    }

    public Identifier vId() {
        return vId;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public Fmp4NativeVideoDecoder.DecodedFrame.Format format() {
        return Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P;
    }

    public boolean upload(byte[] yuv420p, int width, int height) {
        int ySize = width * height;
        int chromaWidth = Math.max(1, width / 2);
        int chromaHeight = Math.max(1, height / 2);
        int uvSize = chromaWidth * chromaHeight;
        if (yuv420p == null || yuv420p.length < ySize + uvSize * 2) {
            return false;
        }
        ensureTextures(width, height, chromaWidth, chromaHeight);
        yTexture.upload(yuv420p, 0);
        uTexture.upload(yuv420p, ySize);
        vTexture.upload(yuv420p, ySize + uvSize);
        return true;
    }

    public boolean upload(ByteBuffer yuv420p, int byteLength, int width, int height) {
        int ySize = width * height;
        int chromaWidth = Math.max(1, width / 2);
        int chromaHeight = Math.max(1, height / 2);
        int uvSize = chromaWidth * chromaHeight;
        int requiredBytes = ySize + uvSize * 2;
        if (yuv420p == null || byteLength < requiredBytes || yuv420p.limit() < requiredBytes) {
            return false;
        }
        return upload(frameBytes(yuv420p, requiredBytes), width, height);
    }

    private static byte[] frameBytes(ByteBuffer buffer, int byteCount) {
        ByteBuffer src = buffer.duplicate();
        src.position(0);
        src.limit(Math.min(src.limit(), byteCount));
        byte[] out = new byte[src.remaining()];
        src.get(out);
        return out;
    }

    private void ensureTextures(int width, int height, int chromaWidth, int chromaHeight) {
        if (yTexture != null && yTexture.matches(width, height)
                && uTexture != null && uTexture.matches(chromaWidth, chromaHeight)
                && vTexture != null && vTexture.matches(chromaWidth, chromaHeight)) {
            this.width = width;
            this.height = height;
            return;
        }
        close();
        this.width = width;
        this.height = height;
        try {
            yTexture = new Yuv420pPlaneTexture(labelPrefix + "_y", width, height);
            uTexture = new Yuv420pPlaneTexture(labelPrefix + "_u", chromaWidth, chromaHeight);
            vTexture = new Yuv420pPlaneTexture(labelPrefix + "_v", chromaWidth, chromaHeight);
            Minecraft.getInstance().getTextureManager().register(yId, yTexture);
            Minecraft.getInstance().getTextureManager().register(uId, uTexture);
            Minecraft.getInstance().getTextureManager().register(vId, vTexture);
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
        if (uTexture != null) {
            Minecraft.getInstance().getTextureManager().release(uId);
            uTexture.close();
            uTexture = null;
        }
        if (vTexture != null) {
            Minecraft.getInstance().getTextureManager().release(vId);
            vTexture.close();
            vTexture = null;
        }
    }
}