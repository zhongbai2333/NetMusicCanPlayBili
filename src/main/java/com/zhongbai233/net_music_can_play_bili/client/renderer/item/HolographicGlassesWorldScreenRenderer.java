package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.RenderVertexUtils;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.YuvVideoRenderTypes;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.MediaBindingData.MediaSource;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.joml.Vector3fc;

import java.util.List;
import java.util.UUID;

/** 佩戴全息眼镜时，在玩家视线前方提交一块跟随玩家移动的世界空间视频屏幕。 */
@EventBusSubscriber(modid = NetMusicCanPlayBili.MODID, value = Dist.CLIENT)
public final class HolographicGlassesWorldScreenRenderer {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("ncpb.holographic_glasses.screen.enabled", "true"));

    private HolographicGlassesWorldScreenRenderer() {
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        if (!ENABLED) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        List<HolographicGlassesItem.ScreenBinding> bindings = HolographicGlassesClient.screenBindings();
        if (bindings.isEmpty()) {
            return;
        }

        boolean rgbaFallback = IrisShaderpackCompat.isShaderPackInUse();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        for (HolographicGlassesItem.ScreenBinding binding : bindings) {
            ScreenQuad quad = computeQuad(camera, binding.config());
            if (quad == null) {
                continue;
            }
            submitBinding(collector, binding.source(), quad, rgbaFallback);
        }
    }

    private static void submitBinding(SubmitNodeCollector collector, MediaSource source, ScreenQuad quad,
            boolean rgbaFallback) {
        if (source == null) {
            return;
        }
        if (source.isMediaDevice()) {
            submitMediaDevice(collector, source.deviceId(), quad, rgbaFallback);
        } else if (source.isProjector()) {
            submitProjector(collector, source, quad, rgbaFallback);
        } else if (source.isTurntable()) {
            submitTurntable(collector, source, quad, rgbaFallback);
        }
    }

    private static void submitMediaDevice(SubmitNodeCollector collector, UUID deviceId, ScreenQuad quad,
            boolean rgbaFallback) {
        if (deviceId == null) {
            return;
        }
        MP4HandheldVideoClient.markVisible(deviceId);
        PoseStack poseStack = new PoseStack();
        if (rgbaFallback) {
            MP4RgbaVideoLayer rgbaLayer = MP4RgbaVideoLayer.forHandheldDevice(deviceId);
            if (!rgbaLayer.uploadLatest(deviceId)) {
                return;
            }
            Identifier textureId = rgbaLayer.textureId();
            collector.submitCustomGeometry(poseStack, RenderTypes.itemCutout(textureId),
                    (pose, buffer) -> emitDoubleSidedQuad(buffer, pose, quad));
        } else {
            MP4Nv12VideoLayer nv12Layer = MP4Nv12VideoLayer.forHandheldDevice(deviceId);
            if (!nv12Layer.uploadLatest(deviceId) || nv12Layer.textureSet() == null) {
                return;
            }
            var textures = nv12Layer.textureSet();
            collector.submitCustomGeometry(poseStack,
                    YuvVideoRenderTypes.nv12Entity(textures.yId(), textures.uId(), textures.vId()),
                    (pose, buffer) -> emitDoubleSidedQuad(buffer, pose, quad));
        }
    }

    private static void submitProjector(SubmitNodeCollector collector, MediaSource source, ScreenQuad quad,
            boolean rgbaFallback) {
        VideoBillboardPreview.ProjectorFrameSnapshot frame = VideoBillboardPreview.currentProjectorFrame(source.pos());
        submitFrame(collector, frame, quad, rgbaFallback);
    }

    private static void submitTurntable(SubmitNodeCollector collector, MediaSource source, ScreenQuad quad,
            boolean rgbaFallback) {
        VideoBillboardPreview.ProjectorFrameSnapshot frame = VideoBillboardPreview.currentTurntableFrame(source.pos());
        submitFrame(collector, frame, quad, rgbaFallback);
    }

    private static void submitFrame(SubmitNodeCollector collector, VideoBillboardPreview.ProjectorFrameSnapshot frame,
            ScreenQuad quad, boolean rgbaFallback) {
        if (!frame.hasFrame()) {
            return;
        }
        PoseStack poseStack = new PoseStack();
        if (!rgbaFallback && frame.yuv()) {
            collector.submitCustomGeometry(poseStack,
                    frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12
                            ? YuvVideoRenderTypes.nv12Entity(frame.yTexture(), frame.uTexture(), frame.vTexture())
                            : YuvVideoRenderTypes.yuv420pEntity(frame.yTexture(), frame.uTexture(), frame.vTexture()),
                    (pose, buffer) -> emitDoubleSidedQuad(buffer, pose, quad));
        } else if (frame.rgbaTexture() != null) {
            collector.submitCustomGeometry(poseStack,
                    frame.emissiveRgba()
                            ? RenderTypes.itemCutout(frame.rgbaTexture())
                            : YuvVideoRenderTypes.videoRgbaEntity(frame.rgbaTexture()),
                    (pose, buffer) -> emitDoubleSidedQuad(buffer, pose, quad));
        }
    }

    private static ScreenQuad computeQuad(Camera camera, HolographicGlassesItem.ScreenConfig config) {
        Vector3fc forward = camera.forwardVector();
        Vector3fc left = camera.leftVector();
        Vector3fc up = camera.upVector();
        Vec3 cameraPos = camera.position();

        float halfHeight = Math.max(0.05F, config.height()) * 0.5F;
        float halfWidth = halfHeight * Math.max(0.1F, config.aspect());
        float rollRad = (float) Math.toRadians(config.roll());
        float cos = (float) Math.cos(rollRad);
        float sin = (float) Math.sin(rollRad);

        float rightX = -left.x();
        float rightY = -left.y();
        float rightZ = -left.z();
        float upX = up.x();
        float upY = up.y();
        float upZ = up.z();

        float rolledRightX = rightX * cos + upX * sin;
        float rolledRightY = rightY * cos + upY * sin;
        float rolledRightZ = rightZ * cos + upZ * sin;
        float rolledUpX = upX * cos - rightX * sin;
        float rolledUpY = upY * cos - rightY * sin;
        float rolledUpZ = upZ * cos - rightZ * sin;

        float cx = forward.x() * config.distance() + rolledRightX * config.offsetX() + rolledUpX * config.offsetY();
        float cy = forward.y() * config.distance() + rolledRightY * config.offsetX() + rolledUpY * config.offsetY();
        float cz = forward.z() * config.distance() + rolledRightZ * config.offsetX() + rolledUpZ * config.offsetY();
        if (!Double.isFinite(cameraPos.x + cx) || !Double.isFinite(cameraPos.y + cy)
                || !Double.isFinite(cameraPos.z + cz)) {
            return null;
        }

        float rx = rolledRightX * halfWidth;
        float ry = rolledRightY * halfWidth;
        float rz = rolledRightZ * halfWidth;
        float ux = rolledUpX * halfHeight;
        float uy = rolledUpY * halfHeight;
        float uz = rolledUpZ * halfHeight;

        return new ScreenQuad(
                cx - rx + ux, cy - ry + uy, cz - rz + uz,
                cx - rx - ux, cy - ry - uy, cz - rz - uz,
                cx + rx - ux, cy + ry - uy, cz + rz - uz,
                cx + rx + ux, cy + ry + uy, cz + rz + uz);
    }

    private static void emitDoubleSidedQuad(VertexConsumer buffer, PoseStack.Pose pose, ScreenQuad quad) {
        emitQuad(buffer, pose, quad, false);
        emitQuad(buffer, pose, quad, true);
    }

    private static void emitQuad(VertexConsumer buffer, PoseStack.Pose pose, ScreenQuad quad, boolean reverse) {
        if (reverse) {
            vertex(buffer, pose, quad.p3x(), quad.p3y(), quad.p3z(), 1.0F, 0.0F);
            vertex(buffer, pose, quad.p2x(), quad.p2y(), quad.p2z(), 1.0F, 1.0F);
            vertex(buffer, pose, quad.p1x(), quad.p1y(), quad.p1z(), 0.0F, 1.0F);
            vertex(buffer, pose, quad.p0x(), quad.p0y(), quad.p0z(), 0.0F, 0.0F);
        } else {
            vertex(buffer, pose, quad.p0x(), quad.p0y(), quad.p0z(), 0.0F, 0.0F);
            vertex(buffer, pose, quad.p1x(), quad.p1y(), quad.p1z(), 0.0F, 1.0F);
            vertex(buffer, pose, quad.p2x(), quad.p2y(), quad.p2z(), 1.0F, 1.0F);
            vertex(buffer, pose, quad.p3x(), quad.p3y(), quad.p3z(), 1.0F, 0.0F);
        }
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float u,
            float v) {
        RenderVertexUtils.texturedVertex(buffer, pose, x, y, z, u, v);
    }

    private record ScreenQuad(float p0x, float p0y, float p0z, float p1x, float p1y, float p1z,
            float p2x, float p2y, float p2z, float p3x, float p3y, float p3z) {
    }
}