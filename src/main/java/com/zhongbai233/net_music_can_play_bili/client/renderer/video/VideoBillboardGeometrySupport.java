package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/** Projector geometry, render-type selection, world anchoring, and visibility culling. */
abstract class VideoBillboardGeometrySupport extends VideoBillboardQuadSupport {
    protected static boolean shouldRenderYuvFrame() {
        // 正常播放由 render.backend 选择 shader；real_bench 的 yuv420 上传会直接写入 yuvTextureSet，
        // 此时即使全局 backend 仍是 rgba，也应该把压测帧提交出来，否则日志在跑但世界里没有画面。
        return LEGACY_TEXTURES.yuv() != null && isCustomYuvShaderAvailable()
                && (CUSTOM_YUV_SHADER_BACKEND || LEGACY_TEXTURES.rgba() == null);
    }

    public static boolean isCustomYuvShaderAvailable() {
        return CUSTOM_YUV_SHADER_BACKEND && !IrisShaderpackCompat.shouldDisableCustomYuvShader();
    }

    static Fmp4NativeVideoDecoder.OutputFormat yuvDecodeFormat() {
        if (NV12_DECODE_BACKEND) {
            return Fmp4NativeVideoDecoder.OutputFormat.NV12;
        }
        if (YUV420_DECODE_BACKEND) {
            return Fmp4NativeVideoDecoder.OutputFormat.YUV420P;
        }
        return Fmp4NativeVideoDecoder.OutputFormat.RGBA;
    }

    protected static boolean isYuvFrameFormat(Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
        return format == Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                || format == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
    }

    protected static void submitProjectorGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector) {
        submitProjectorGeometry(event, minecraft, camera, projector, TEXTURE_ID, width, height);
    }

    static void submitProjectorGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, Identifier renderTextureId, int textureWidth, int textureHeight) {
        submitProjectorGeometry(event, minecraft, camera, projector, renderTextureId, textureWidth, textureHeight,
                0.0D, YuvVideoRenderTypes.videoRgbaEntity(renderTextureId), "projector-rgba");
    }

    static void submitProjectorEmissiveGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, Identifier renderTextureId, int textureWidth, int textureHeight) {
        submitProjectorGeometry(event, minecraft, camera, projector, renderTextureId, textureWidth, textureHeight,
                0.0D, shaderpackSafeEmissiveRgba(renderTextureId), "projector-rgba-placeholder");
    }

    static void submitProjectorPrivacyOverlay(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector) {
        PreviewQuad quad = computePreviewQuad(minecraft, camera, projector,
                320, 180, true, true);
        if (quad == null) {
            return;
        }
        PoseStack poseStack = new PoseStack();
        HolographicPrivacyOverlay.submit(event.getSubmitNodeCollector(), poseStack,
                quad.p0x(), quad.p0y(), quad.p0z() + 0.003F,
                quad.p1x(), quad.p1y(), quad.p1z() + 0.003F,
                quad.p2x(), quad.p2y(), quad.p2z() + 0.003F,
                quad.p3x(), quad.p3y(), quad.p3z() + 0.003F);
    }

    static boolean drawProjectorPrivacyOverlayImmediate(RenderLevelStageEvent event, Minecraft minecraft,
            Camera camera, VideoProjectorBlockEntity projector, String route) {
        PreviewQuad quad = computePreviewQuad(minecraft, camera, projector,
                320, 180, true, true);
        if (quad == null) {
            return false;
        }
        RenderType renderType = YuvVideoRenderTypes.videoRgbaEntity(HolographicPrivacyOverlay.textureId());
        BufferBuilder builder = Tesselator.getInstance().begin(renderType.mode(), renderType.format());
        PoseStack poseStack = "identity".equals(YUV_IMMEDIATE_POSE) ? new PoseStack() : event.getPoseStack();
        PoseStack.Pose pose = poseStack.last();
        emitQuad(builder, pose, quad.p0x(), quad.p0y(), quad.p0z() + 0.003F,
                quad.p1x(), quad.p1y(), quad.p1z() + 0.003F,
                quad.p2x(), quad.p2y(), quad.p2z() + 0.003F,
                quad.p3x(), quad.p3y(), quad.p3z() + 0.003F, false);
        emitQuad(builder, pose, quad.p0x(), quad.p0y(), quad.p0z() + 0.003F,
                quad.p1x(), quad.p1y(), quad.p1z() + 0.003F,
                quad.p2x(), quad.p2y(), quad.p2z() + 0.003F,
                quad.p3x(), quad.p3y(), quad.p3z() + 0.003F, true);
        MeshData mesh = builder.build();
        if (mesh == null) {
            return false;
        }
        drawWithEventModelView(renderType, mesh, event);
        return true;
    }

    static void submitProjectorViewDepthOffsetGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft,
            Camera camera, VideoProjectorBlockEntity projector, Identifier renderTextureId, int textureWidth,
            int textureHeight, double viewDepthOffset) {
        submitProjectorGeometry(event, minecraft, camera, projector, renderTextureId, textureWidth, textureHeight,
                Math.max(0.0D, viewDepthOffset), YuvVideoRenderTypes.videoRgbaEntity(renderTextureId),
                "projector-rgba-depth-offset");
    }

    protected static void submitProjectorGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, Identifier renderTextureId, int textureWidth, int textureHeight,
            double viewDepthOffset, RenderType renderType, String route) {
        float scale = Math.abs(projector.getProjectionScale());
        float aspect = textureWidth / (float) textureHeight;
        float halfHeight = HEIGHT * scale * 0.5F;
        float halfWidth = halfHeight * aspect;

        if (!ensureWorldAnchor(minecraft, camera, projector)) {
            return;
        }
        Vec3 cameraPos = camera.position();
        if (!isProjectorWithinRenderDistance(cameraPos, projector, anchorX, anchorY, anchorZ, aspect)) {
            return;
        }
        double dx = anchorX - cameraPos.x;
        double dy = anchorY - cameraPos.y;
        double dz = anchorZ - cameraPos.z;

        double yawRad = Math.toRadians(anchorYawDeg);
        double pitchRad = Math.toRadians(projector.getProjectionPitch());
        float rightX = (float) Math.cos(yawRad);
        float rightZ = (float) Math.sin(yawRad);
        float forwardX = (float) -Math.sin(yawRad);
        float forwardZ = (float) Math.cos(yawRad);
        float upX = (float) (forwardX * Math.sin(pitchRad));
        float upY = (float) Math.cos(pitchRad);
        float upZ = (float) (forwardZ * Math.sin(pitchRad));

        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double depthOffsetScale = viewDepthOffset > 0.0D && distance > 1.0e-4D ? viewDepthOffset / distance : 0.0D;
        float cx = (float) (anchorX - cameraPos.x + dx * depthOffsetScale);
        float cy = (float) (anchorY - cameraPos.y + dy * depthOffsetScale);
        float cz = (float) (anchorZ - cameraPos.z + dz * depthOffsetScale);
        float rx = rightX * halfWidth;
        float rz = rightZ * halfWidth;
        float ux = upX * halfHeight;
        float uy = upY * halfHeight;
        float uz = upZ * halfHeight;

        final float p0x = cx - rx + ux;
        final float p0y = cy + uy;
        final float p0z = cz - rz + uz;
        final float p1x = cx - rx - ux;
        final float p1y = cy - uy;
        final float p1z = cz - rz - uz;
        final float p2x = cx + rx - ux;
        final float p2y = cy - uy;
        final float p2z = cz + rz - uz;
        final float p3x = cx + rx + ux;
        final float p3y = cy + uy;
        final float p3z = cz + rz + uz;

        PoseStack poseStack = new PoseStack();
        logFirstPreviewSubmit(false, textureWidth, textureHeight, camera, anchorX, anchorY, anchorZ,
                route);
        event.getSubmitNodeCollector().submitCustomGeometry(
                poseStack,
                renderType,
                (pose, buffer) -> {
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            false);
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            true);
                });
    }

    static void submitProjectorYuvGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, VideoYuvTextureSet textures) {
        submitProjectorGeometry(event, minecraft, camera, projector,
                yuvRenderTypeForCurrentIrisProgram(textures),
                textures.width(), textures.height());
    }

    /**
     * Submit the current projector frame in the caller's local BER coordinate
     * space.
     *
     * <p>
     * This path does not query {@link Minecraft#level}, so simulated block entities
     * (for example a block carried by
     * an entity renderer) can reuse the already-correct BER pose stack.
     * </p>
     */
    public static boolean submitProjectorFrameOnPose(
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            PoseStack poseStack,
            ProjectorFrameSnapshot frame,
            float halfWidth,
            float halfHeight) {
        return submitProjectorFrameOnPose(collector, poseStack, frame, halfWidth, halfHeight,
                frame != null ? frame.rgbaDepthOffset() : 0.0F);
    }

    public static boolean submitProjectorFrameOnPose(
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            PoseStack poseStack,
            ProjectorFrameSnapshot frame,
            float halfWidth,
            float halfHeight,
            float rgbaDepthOffset) {
        if (collector == null || poseStack == null || frame == null || !frame.hasFrame()
                || frame.width() <= 0 || frame.height() <= 0 || halfWidth <= 0.0F || halfHeight <= 0.0F) {
            return false;
        }
        if (frame.yuv()) {
            if (!isCustomYuvShaderAvailable()
                    || frame.yTexture() == null || frame.uTexture() == null || frame.vTexture() == null) {
                return false;
            }
            submitLocalTexturedQuadSingle(collector, poseStack, yuvRenderTypeForSnapshot(frame), halfWidth, halfHeight,
                    0.0F, 1.0F);
            return true;
        }
        if (frame.rgbaTexture() == null) {
            return false;
        }
        submitLocalTexturedQuad(collector, poseStack,
                frame.emissiveRgba()
                        ? shaderpackSafeEmissiveRgba(frame.rgbaTexture())
                        : YuvVideoRenderTypes.videoRgbaEntity(frame.rgbaTexture()),
                -halfWidth, halfHeight, halfWidth, -halfHeight, rgbaDepthOffset, 1.0F);
        if (frame.loadingProgressOverlay()) {
            submitLoadingProgressOnPose(collector, poseStack, halfWidth, halfHeight);
        }
        return true;
    }

    public static boolean submitProjectorFrameOnPose(
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            PoseStack poseStack,
            ProjectorFrameSnapshot frame,
            float halfWidth,
            float halfHeight,
            float rgbaDepthOffset,
            float opacity) {
        VideoOpacityRoute opacityRoute = VideoOpacityRoute.choose(opacity);
        if (opacityRoute == VideoOpacityRoute.SKIP) {
            return false;
        }
        float normalizedOpacity = VideoOpacityRoute.normalize(opacity);
        if (frame == null || !frame.hasFrame() || frame.width() <= 0 || frame.height() <= 0
                || halfWidth <= 0.0F || halfHeight <= 0.0F) {
            return false;
        }
        if (frame.yuv()) {
            if (!isCustomYuvShaderAvailable()
                    || frame.yTexture() == null || frame.uTexture() == null || frame.vTexture() == null) {
                return false;
            }
            submitLocalTexturedQuadSingle(collector, poseStack, yuvRenderTypeForSnapshot(frame), halfWidth, halfHeight,
                    0.0F, normalizedOpacity);
            return true;
        }
        if (frame.rgbaTexture() == null) {
            return false;
        }
        submitLocalTexturedQuad(collector, poseStack,
                frame.emissiveRgba()
                ? (opacityRoute == VideoOpacityRoute.TRANSLUCENT
                    ? shaderpackSafeTranslucentRgba(frame.rgbaTexture())
                    : shaderpackSafeEmissiveRgba(frame.rgbaTexture()))
                : (opacityRoute == VideoOpacityRoute.TRANSLUCENT
                    ? shaderpackSafeTranslucentRgba(frame.rgbaTexture())
                    : YuvVideoRenderTypes.videoRgbaEntity(frame.rgbaTexture())),
                -halfWidth, halfHeight, halfWidth, -halfHeight, rgbaDepthOffset, normalizedOpacity);
        if (frame.loadingProgressOverlay()) {
            submitLoadingProgressOnPose(collector, poseStack, halfWidth, halfHeight, normalizedOpacity);
        }
        return true;
    }

    public static boolean submitLoadingProgressOnPose(
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            PoseStack poseStack,
            float halfWidth,
            float halfHeight) {
        return submitLoadingProgressOnPose(collector, poseStack, halfWidth, halfHeight, 1.0F);
    }

    protected static boolean submitLoadingProgressOnPose(
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            PoseStack poseStack,
            float halfWidth,
            float halfHeight,
            float opacity) {
        if (collector == null || poseStack == null || halfWidth <= 0.0F || halfHeight <= 0.0F) {
            return false;
        }
        VideoOpacityRoute route = VideoOpacityRoute.choose(opacity);
        if (route == VideoOpacityRoute.SKIP) {
            return false;
        }
        float normalizedOpacity = VideoOpacityRoute.normalize(opacity);
        RenderType frameRenderType = route == VideoOpacityRoute.TRANSLUCENT
                ? shaderpackSafeTranslucentRgba(LOADING_PROGRESS_FRAME_TEXTURE)
                : shaderpackSafeEmissiveRgba(LOADING_PROGRESS_FRAME_TEXTURE);
        RenderType segmentRenderType = route == VideoOpacityRoute.TRANSLUCENT
                ? shaderpackSafeTranslucentRgba(LOADING_PROGRESS_SEGMENT_TEXTURE)
                : shaderpackSafeEmissiveRgba(LOADING_PROGRESS_SEGMENT_TEXTURE);
        submitLocalTexturedQuad(collector, poseStack, frameRenderType,
                pixelLeft(LOADING_PROGRESS_X, halfWidth),
                pixelTop(LOADING_PROGRESS_Y, halfHeight),
                pixelRight(LOADING_PROGRESS_X + LOADING_PROGRESS_W, halfWidth),
                pixelBottom(LOADING_PROGRESS_Y + LOADING_PROGRESS_H, halfHeight),
                0.004F, normalizedOpacity);
        submitLocalTexturedQuad(collector, poseStack, frameRenderType,
                pixelLeft(LOADING_PROGRESS_X, halfWidth),
                pixelTop(LOADING_PROGRESS_Y, halfHeight),
                pixelRight(LOADING_PROGRESS_X + LOADING_PROGRESS_W, halfWidth),
                pixelBottom(LOADING_PROGRESS_Y + LOADING_PROGRESS_H, halfHeight),
                -0.004F, normalizedOpacity);
        int movingX = LOADING_PROGRESS_X + 2 + (int) (((System.nanoTime() / 12_000_000L)
                % Math.max(1, LOADING_PROGRESS_W - LOADING_PROGRESS_SEGMENT_W - 4)));
        submitLocalTexturedQuad(collector, poseStack, segmentRenderType,
                pixelLeft(movingX, halfWidth),
                pixelTop(LOADING_PROGRESS_Y + 2, halfHeight),
                pixelRight(movingX + LOADING_PROGRESS_SEGMENT_W, halfWidth),
                pixelBottom(LOADING_PROGRESS_Y + 2 + LOADING_PROGRESS_SEGMENT_H, halfHeight),
                0.006F, normalizedOpacity);
        submitLocalTexturedQuad(collector, poseStack, segmentRenderType,
                pixelLeft(movingX, halfWidth),
                pixelTop(LOADING_PROGRESS_Y + 2, halfHeight),
                pixelRight(movingX + LOADING_PROGRESS_SEGMENT_W, halfWidth),
                pixelBottom(LOADING_PROGRESS_Y + 2 + LOADING_PROGRESS_SEGMENT_H, halfHeight),
                -0.006F, normalizedOpacity);
        return true;
    }

    protected static RenderType shaderpackSafeEmissiveRgba(Identifier texture) {
        return IrisShaderpackCompat.isShaderPackInUse()
                ? YuvVideoRenderTypes.videoRgbaEmissiveEntity(texture)
                : RenderTypes.itemCutout(texture);
    }

    protected static RenderType shaderpackSafeTranslucentRgba(Identifier texture) {
        return IrisShaderpackCompat.isShaderPackInUse()
                ? YuvVideoRenderTypes.videoRgbaTranslucentEntity(texture)
                : RenderTypes.itemTranslucent(texture);
    }

    public static boolean submitProjectorPrivacyOverlayOnPose(
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            PoseStack poseStack,
            float halfWidth,
            float halfHeight) {
        if (collector == null || poseStack == null || halfWidth <= 0.0F || halfHeight <= 0.0F) {
            return false;
        }
        HolographicPrivacyOverlay.submit(collector, poseStack,
                -halfWidth, halfHeight, 0.003F,
                -halfWidth, -halfHeight, 0.003F,
                halfWidth, -halfHeight, 0.003F,
                halfWidth, halfHeight, 0.003F);
        return true;
    }

    protected static void submitLocalTexturedQuad(
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            PoseStack poseStack,
            RenderType renderType,
            float left,
            float top,
            float right,
            float bottom,
            float z,
            float opacity) {
        collector.submitCustomGeometry(
                poseStack,
                renderType,
                (pose, buffer) -> {
                    emitQuad(buffer, pose,
                            left, top, z,
                            left, bottom, z,
                            right, bottom, z,
                            right, top, z,
                            false, opacity);
                    emitQuad(buffer, pose,
                            left, top, z,
                            left, bottom, z,
                            right, bottom, z,
                            right, top, z,
                            true, opacity);
                });
    }

    /** YUV pipeline 已关闭 cull；透明表面只提交一次，避免共面正反面争用深度与重复混合。 */
    protected static void submitLocalTexturedQuadSingle(
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            PoseStack poseStack,
            RenderType renderType,
            float halfWidth,
            float halfHeight,
            float z,
            float opacity) {
        collector.submitCustomGeometry(
                poseStack,
                renderType,
                (pose, buffer) -> emitQuad(buffer, pose,
                        -halfWidth, halfHeight, z,
                        -halfWidth, -halfHeight, z,
                        halfWidth, -halfHeight, z,
                        halfWidth, halfHeight, z,
                        false, opacity));
    }

    protected static float pixelLeft(int x, float halfWidth) {
        return -halfWidth + x * (halfWidth * 2.0F) / LOADING_PLACEHOLDER_WIDTH;
    }

    protected static float pixelRight(int x, float halfWidth) {
        return pixelLeft(x, halfWidth);
    }

    protected static float pixelTop(int y, float halfHeight) {
        return halfHeight - y * (halfHeight * 2.0F) / LOADING_PLACEHOLDER_HEIGHT;
    }

    protected static float pixelBottom(int y, float halfHeight) {
        return pixelTop(y, halfHeight);
    }

    protected static net.minecraft.client.renderer.rendertype.RenderType yuvRenderTypeForSnapshot(
            ProjectorFrameSnapshot frame) {
        if (IrisShaderpackCompat.shouldForceSafeProbeRenderType()
                || IrisShaderpackCompat.shouldUseSingleSamplerProbe()
                || IrisShaderpackCompat.isTexturedProbeProgram()) {
            if (loggedIrisYuvRenderType.compareAndSet(false, true)) {
                LOGGER.debug(
                        "Iris/YUV: 首个视频 YUV draw 使用 TEXTURED probe RenderType，绑定 Sampler0/1/2=Y plane，占位规避 shaderpack sampler 校验；非真彩 YUV");
            }
            return YuvVideoRenderTypes.yOnlyTexturedProbeEntity(frame.yTexture());
        }
        if (loggedIrisYuvRenderType.compareAndSet(false, true)) {
            LOGGER.debug("Iris/YUV: 首个视频 YUV draw 使用 {} RenderType", frame.format());
        }
        if (frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12) {
            return YuvVideoRenderTypes.nv12Entity(frame.yTexture(), frame.uTexture(), frame.vTexture());
        }
        return YuvVideoRenderTypes.yuv420pEntity(frame.yTexture(), frame.uTexture(), frame.vTexture());
    }

    protected static net.minecraft.client.renderer.rendertype.RenderType yuvRenderTypeForCurrentIrisProgram(
            VideoYuvTextureSet textures) {
        if (IrisShaderpackCompat.shouldForceSafeProbeRenderType()
                || IrisShaderpackCompat.shouldUseSingleSamplerProbe()
                || IrisShaderpackCompat.isTexturedProbeProgram()) {
            if (loggedIrisYuvRenderType.compareAndSet(false, true)) {
                LOGGER.debug(
                        "Iris/YUV: 首个视频 YUV draw 使用 TEXTURED probe RenderType，绑定 Sampler0/1/2=Y plane，占位规避 shaderpack sampler 校验；非真彩 YUV");
            }
            return YuvVideoRenderTypes.yOnlyTexturedProbeEntity(textures.yId());
        }
        if (loggedIrisYuvRenderType.compareAndSet(false, true)) {
            LOGGER.debug("Iris/YUV: 首个视频 YUV draw 使用 {} RenderType", textures.format());
        }
        if (textures.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12) {
            return YuvVideoRenderTypes.nv12Entity(textures.yId(), textures.uId(), textures.vId());
        }
        return YuvVideoRenderTypes.yuv420pEntity(textures.yId(), textures.uId(), textures.vId());
    }

    protected static void submitProjectorGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, net.minecraft.client.renderer.rendertype.RenderType renderType,
            int textureWidth, int textureHeight) {
        float scale = Math.abs(projector.getProjectionScale());
        float aspect = textureWidth / (float) textureHeight;
        float halfHeight = HEIGHT * scale * 0.5F;
        float halfWidth = halfHeight * aspect;

        if (!ensureWorldAnchor(minecraft, camera, projector)) {
            return;
        }
        Vec3 cameraPos = camera.position();
        if (!isProjectorWithinRenderDistance(cameraPos, projector, anchorX, anchorY, anchorZ, aspect)) {
            return;
        }

        double yawRad = Math.toRadians(anchorYawDeg);
        double pitchRad = Math.toRadians(projector.getProjectionPitch());
        float rightX = (float) Math.cos(yawRad);
        float rightZ = (float) Math.sin(yawRad);
        float forwardX = (float) -Math.sin(yawRad);
        float forwardZ = (float) Math.cos(yawRad);
        float upX = (float) (forwardX * Math.sin(pitchRad));
        float upY = (float) Math.cos(pitchRad);
        float upZ = (float) (forwardZ * Math.sin(pitchRad));

        float cx = (float) (anchorX - cameraPos.x);
        float cy = (float) (anchorY - cameraPos.y);
        float cz = (float) (anchorZ - cameraPos.z);
        float rx = rightX * halfWidth;
        float rz = rightZ * halfWidth;
        float ux = upX * halfHeight;
        float uy = upY * halfHeight;
        float uz = upZ * halfHeight;

        final float p0x = cx - rx + ux;
        final float p0y = cy + uy;
        final float p0z = cz - rz + uz;
        final float p1x = cx - rx - ux;
        final float p1y = cy - uy;
        final float p1z = cz - rz - uz;
        final float p2x = cx + rx - ux;
        final float p2y = cy - uy;
        final float p2z = cz + rz - uz;
        final float p3x = cx + rx + ux;
        final float p3y = cy + uy;
        final float p3z = cz + rz + uz;

        PoseStack poseStack = new PoseStack();
        logFirstPreviewSubmit(true, textureWidth, textureHeight, camera, anchorX, anchorY, anchorZ,
                "projector-yuv");
        event.getSubmitNodeCollector().submitCustomGeometry(
                poseStack,
                renderType,
                (pose, buffer) -> {
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            false);
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            true);
                });
    }

    protected static void logFirstPreviewSubmit(boolean yuv, int textureWidth, int textureHeight, Camera camera,
            double centerX, double centerY, double centerZ, String route) {
        if (firstPreviewSubmitLogged) {
            return;
        }
        firstPreviewSubmitLogged = true;
        Vec3 cameraPos = camera.position();
        double dx = centerX - cameraPos.x;
        double dy = centerY - cameraPos.y;
        double dz = centerZ - cameraPos.z;
        LOGGER.debug("视频 quad 已提交: route={}, yuv={}, size={}x{}, distance={}, "
                + "anchor=({}, {}, {}), camera=({}, {}, {}), shaderAvailable={}, yuvTextureSet={}", route, yuv,
                textureWidth, textureHeight,
                String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(dx * dx + dy * dy + dz * dz)),
                String.format(java.util.Locale.ROOT, "%.2f", centerX),
                String.format(java.util.Locale.ROOT, "%.2f", centerY),
                String.format(java.util.Locale.ROOT, "%.2f", centerZ),
                String.format(java.util.Locale.ROOT, "%.2f", cameraPos.x),
                String.format(java.util.Locale.ROOT, "%.2f", cameraPos.y),
                String.format(java.util.Locale.ROOT, "%.2f", cameraPos.z),
                isCustomYuvShaderAvailable(), LEGACY_TEXTURES.yuv() != null);
    }

}
