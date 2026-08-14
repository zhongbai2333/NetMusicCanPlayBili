package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.NativeImage;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Owns one playback session's RGBA double-buffer or YUV texture set. */
final class VideoPlaybackTextures {
    private final String sessionId;
    private final Identifier firstTextureId;
    private final Identifier secondTextureId;
    private final Identifier yTextureId;
    private final Identifier uTextureId;
    private final Identifier vTextureId;
    private DynamicTexture frontTexture;
    private DynamicTexture backTexture;
    private VideoYuvTextureSet yuvTextureSet;
    private Identifier frontTextureId;
    private Identifier backTextureId;

    VideoPlaybackTextures(String sessionId) {
        this.sessionId = sessionId;
        String suffix = Integer.toUnsignedString(sessionId.hashCode(), 16);
        this.firstTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + suffix + "_a");
        this.secondTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + suffix + "_b");
        this.yTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + suffix + "_y");
        this.uTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + suffix + "_u");
        this.vTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + suffix + "_v");
        this.frontTextureId = firstTextureId;
        this.backTextureId = secondTextureId;
    }

    boolean uploadDecodedFrame(VideoBillboardPreview.DecodedFrame frame, int width, int height) {
        if (VideoBillboardPreview.isCustomYuvShaderAvailable() && isYuvFrameFormat(frame.format())) {
            return uploadYuv(frame, width, height);
        }
        return uploadRgba(Yuv420pConverter.toUploadRgba(frame, width, height), width, height);
    }

    boolean uploadRgba(byte[] rgba, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || rgba.length < width * height * 4) {
            return false;
        }
        ensureRgbaTextures(width, height);
        NativeImage image = backTexture.getPixels();
        if (image == null || image.isClosed()) {
            return false;
        }
        VideoFrameUploader.uploadRgba(image, rgba, width, height);
        backTexture.upload();
        swapTextures();
        releaseYuvTextures();
        return true;
    }

    boolean uploadYuv(VideoBillboardPreview.DecodedFrame frame, int width, int height) {
        if (Minecraft.getInstance().level == null) {
            return false;
        }
        ensureYuvTextureSet(frame.format());
        if (!uploadYuvFrameData(frame, width, height)) {
            return false;
        }
        releaseRgbaTextures();
        return true;
    }

    VideoBillboardPreview.ProjectorFrameSnapshot snapshot(int width, int height) {
        if (frontTexture != null) {
            return new VideoBillboardPreview.ProjectorFrameSnapshot(true, false, frontTextureId,
                    null, null, null, Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA,
                    width, height, false, false, 0.0F);
        }
        if (yuvTextureSet != null) {
            return new VideoBillboardPreview.ProjectorFrameSnapshot(true, true, null, yuvTextureSet.yId(),
                    yuvTextureSet.uId(), yuvTextureSet.vId(), yuvTextureSet.format(), yuvTextureSet.width(),
                    yuvTextureSet.height(), false, false, 0.0F);
        }
        return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
    }

    boolean hasRgbaTexture() {
        return frontTexture != null;
    }

    Identifier rgbaTextureId() {
        return frontTextureId;
    }

    VideoYuvTextureSet yuvTextureSet() {
        return yuvTextureSet;
    }

    boolean hasYuvTexture() {
        return yuvTextureSet != null;
    }

    void release() {
        releaseRgbaTextures();
        releaseYuvTextures();
    }

    private boolean uploadYuvFrameData(VideoBillboardPreview.DecodedFrame frame, int width, int height) {
        if (yuvTextureSet == null || frame == null || yuvTextureSet.format() != frame.format()) {
            return false;
        }
        java.nio.ByteBuffer buffer = frame.buffer();
        return buffer != null
                ? yuvTextureSet.upload(buffer, frame.byteLength(), width, height)
                : yuvTextureSet.upload(frame.data(), width, height);
    }

    private void ensureYuvTextureSet(Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
        Fmp4NativeVideoDecoder.DecodedFrame.Format normalized =
                format == Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                        ? Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                        : Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
        if (yuvTextureSet != null && yuvTextureSet.format() == normalized) {
            return;
        }
        releaseYuvTextures();
        yuvTextureSet = normalized == Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                ? new Yuv420pTextureSet(yTextureId, uTextureId, vTextureId,
                        "bili_video_" + sessionId + "_yuv420p")
                : new Nv12TextureSet(yTextureId, uTextureId, yTextureId,
                        "bili_video_" + sessionId + "_nv12");
    }

    private static boolean isYuvFrameFormat(Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
        return format == Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                || format == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
    }

    private void ensureRgbaTextures(int width, int height) {
        if (frontTexture != null && backTexture != null) {
            NativeImage image = frontTexture.getPixels();
            NativeImage backImage = backTexture.getPixels();
            if (image != null && !image.isClosed() && image.getWidth() == width && image.getHeight() == height
                    && backImage != null && !backImage.isClosed()
                    && backImage.getWidth() == width && backImage.getHeight() == height) {
                return;
            }
        }
        release();
        frontTexture = new DynamicTexture("bili_video_" + sessionId + "_front", width, height, false);
        backTexture = new DynamicTexture("bili_video_" + sessionId + "_back", width, height, false);
        frontTextureId = firstTextureId;
        backTextureId = secondTextureId;
        Minecraft.getInstance().getTextureManager().register(frontTextureId, frontTexture);
        Minecraft.getInstance().getTextureManager().register(backTextureId, backTexture);
    }

    private void releaseRgbaTextures() {
        if (frontTexture != null) {
            Minecraft.getInstance().getTextureManager().release(frontTextureId);
            frontTexture.close();
            frontTexture = null;
        }
        if (backTexture != null && !backTextureId.equals(frontTextureId)) {
            Minecraft.getInstance().getTextureManager().release(backTextureId);
            backTexture.close();
            backTexture = null;
        } else if (backTexture != null) {
            backTexture.close();
            backTexture = null;
        }
        frontTextureId = firstTextureId;
        backTextureId = secondTextureId;
    }

    private void releaseYuvTextures() {
        if (yuvTextureSet != null) {
            yuvTextureSet.close();
            yuvTextureSet = null;
        }
    }

    private void swapTextures() {
        DynamicTexture oldFront = frontTexture;
        frontTexture = backTexture;
        backTexture = oldFront;
        Identifier oldFrontId = frontTextureId;
        frontTextureId = backTextureId;
        backTextureId = oldFrontId;
    }
}
