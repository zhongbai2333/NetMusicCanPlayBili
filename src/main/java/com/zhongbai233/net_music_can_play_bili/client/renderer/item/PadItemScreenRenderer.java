package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.PadClient;
import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.client.renderer.RenderVertexUtils;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.YuvVideoRenderTypes;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;

/** 通过离屏 GUI 纹理渲染第一人称手持 Pad。 */
public final class PadItemScreenRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final boolean VIDEO_DEBUG_LOG = Boolean.getBoolean("ncpb.pad.video.debug_log");
    private static final boolean VIDEO_RENDERDOC_PROBE = Boolean.getBoolean("ncpb.pad.video.renderdoc_probe");
    private static final float HELD_SURFACE_X = 0.53F;
    private static final float HELD_SURFACE_Y = 0.08F;
    private static final float HELD_SURFACE_Z = -1.08F;
    private static final float HELD_SURFACE_SCALE = 1.56F;
    private static final float HELD_SURFACE_LEFT_SHIFT = Float.parseFloat(
            System.getProperty("ncpb.pad.handheld_left_shift", "0.05"));
    private static final float HOVER_TILT_DEGREES = 3.0F;
    private static final float DEVICE_BORDER = 0.040F;
    private static final float DEVICE_THICKNESS = 0.060F;
    private static final float SCREEN_FACE_Z_OFFSET = 0.020F;
    private static final float SCREEN_TEXTURE_Z_OFFSET = 0.024F;
    private static final float VIDEO_UNDERLAY_Z_OFFSET = SCREEN_TEXTURE_Z_OFFSET - 0.001F;
    private static final float VIDEO_TEXTURE_Z_OFFSET = SCREEN_TEXTURE_Z_OFFSET + 0.002F;
    private static final int MAP_LAYER_TICK_INTERVAL_TICKS = Math.max(1,
            Integer.getInteger("ncpb.pad.map_layer_tick_interval_ticks", 5));
    private static final UUID FALLBACK_DEVICE_ID = new UUID(0L, 0L);
    private static final Map<UUID, PadGuiTexture> GUI_TEXTURES = new ConcurrentHashMap<>();
    private static int mapLayerTickCountdown;

    private PadItemScreenRenderer() {
    }

    public static void warmup() {
        textureFor(null).warmup();
    }

    public static void releaseAll() {
        GUI_TEXTURES.values().forEach(texture -> texture.close());
        GUI_TEXTURES.clear();
        MP4Nv12VideoLayer.releaseAllHandheld();
        MP4RgbaVideoLayer.releaseAllHandheld();
    }

    public static void releaseVideoLayers(UUID deviceId) {
        MP4Nv12VideoLayer.releaseHandheld(deviceId);
        MP4RgbaVideoLayer.releaseHandheld(deviceId);
    }

    private static boolean isLockedPad(UUID deviceId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || deviceId == null) {
            return false;
        }
        return PadClient.hasLockedIndexedPad(deviceId);
    }

    public static void renderHeldOffscreenGuiFrameStart() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        renderHeldOffscreenGuiFrameStart(minecraft.player.getMainHandItem());
        renderHeldOffscreenGuiFrameStart(minecraft.player.getOffhandItem());
    }

    public static void tickHeldMapLayers() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            mapLayerTickCountdown = 0;
            return;
        }
        if (mapLayerTickCountdown > 0) {
            mapLayerTickCountdown--;
            return;
        }
        mapLayerTickCountdown = MAP_LAYER_TICK_INTERVAL_TICKS - 1;
        tickHeldMapLayer(minecraft.player.getMainHandItem());
        tickHeldMapLayer(minecraft.player.getOffhandItem());
    }

    private static void tickHeldMapLayer(ItemStack stack) {
        if (!(stack.getItem() instanceof PadItem)) {
            return;
        }
        UUID deviceId = PadItem.readDeviceId(stack);
        textureFor(deviceId).tickMapLayer(deviceId);
    }

    private static void renderHeldOffscreenGuiFrameStart(ItemStack stack) {
        if (!(stack.getItem() instanceof PadItem)) {
            return;
        }
        UUID deviceId = PadItem.readDeviceId(stack);
        markVideoSurfaceVisible(deviceId);
        textureFor(deviceId).renderFrameStart(deviceId);
    }

    public interface ArmRenderer {
        void renderMapHand(PoseStack poseStack, SubmitNodeCollector collector, int light, HumanoidArm arm);

        void renderPlayerArm(PoseStack poseStack, SubmitNodeCollector collector, int light, float equipProgress,
                float swingProgress, HumanoidArm arm);
    }

    public static void renderMapLike(AbstractClientPlayer player, float partialTick, float pitch,
            InteractionHand hand, ItemStack stack, float swingProgress, float equipProgress, PoseStack poseStack,
            SubmitNodeCollector collector, int light, ArmRenderer armRenderer) {
        boolean mainHand = hand == InteractionHand.MAIN_HAND;
        HumanoidArm arm = mainHand ? player.getMainArm() : player.getMainArm().getOpposite();
        UUID deviceId = stack.getItem() instanceof PadItem ? PadItem.readDeviceId(stack) : null;
        if (deviceId != null) {
            markVideoSurfaceVisible(deviceId);
            textureFor(deviceId).renderFrameStart(deviceId, partialTick);
        }

        poseStack.pushPose();
        if (PadFocusState.activeFor(hand)) {
            applyFocusedMapPose(arm, poseStack);
            renderFocusedHand(player, partialTick, poseStack, collector, light, arm, armRenderer);
        } else {
            applyOneHandedMapPose(arm, swingProgress, equipProgress, poseStack, collector, light, armRenderer);
        }
        poseStack.translate(-HELD_SURFACE_LEFT_SHIFT, 0.0F, 0.0F);
        submitTexturedSurface(poseStack, collector, deviceId);
        poseStack.popPose();
    }

    private static void renderFocusedHand(AbstractClientPlayer player, float partialTick, PoseStack poseStack,
            SubmitNodeCollector collector, int light, HumanoidArm arm, ArmRenderer armRenderer) {
        if (player.isInvisible()) {
            return;
        }
        poseStack.pushPose();
        float side = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.translate(side * -0.085F, -0.083F, 0.255F);
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * 3.0F));
        armRenderer.renderPlayerArm(poseStack, collector, light, 0.0F, 0.0F, arm);
        poseStack.popPose();
    }

    private static void applyFocusedMapPose(HumanoidArm arm, PoseStack poseStack) {
        float side = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.translate(side * 0.055F, -0.105F, 0.0F);
        poseStack.translate(side * HELD_SURFACE_X, HELD_SURFACE_Y, HELD_SURFACE_Z);
        poseStack.scale(HELD_SURFACE_SCALE, HELD_SURFACE_SCALE, HELD_SURFACE_SCALE);
    }

    private static void applyOneHandedMapPose(HumanoidArm arm, float swingProgress, float equipProgress,
            PoseStack poseStack, SubmitNodeCollector collector, int light, ArmRenderer armRenderer) {
        float side = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.translate(side * 0.055F, -0.105F, 0.0F);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && !minecraft.player.isInvisible()) {
            poseStack.pushPose();
            poseStack.mulPose(Axis.ZP.rotationDegrees(side * 6.0F));
            armRenderer.renderPlayerArm(poseStack, collector, light, equipProgress, swingProgress, arm);
            poseStack.popPose();
        }
        poseStack.translate(side * HELD_SURFACE_X, HELD_SURFACE_Y + equipProgress * -0.85F, HELD_SURFACE_Z);
        poseStack.scale(HELD_SURFACE_SCALE, HELD_SURFACE_SCALE, HELD_SURFACE_SCALE);
    }

    private static void submitTexturedSurface(PoseStack poseStack, SubmitNodeCollector collector, UUID deviceId) {
        PadFocusState.renderTick();
        PadGuiTexture guiTexture = textureFor(deviceId);
        Identifier textureId = guiTexture.textureId(deviceId);
        Identifier bodyTextureId = guiTexture.whiteTextureId();
        boolean locked = isLockedPad(deviceId);
        markVideoSurfaceVisible(deviceId);
        boolean videoUnderlay = locked && hasVideoFrame(deviceId);
        float aspect = PadGuiTexture.WIDTH / (float) PadGuiTexture.HEIGHT;
        float halfHeight = 0.39F;
        float halfWidth = halfHeight * aspect;

        final float bx0 = -halfWidth - DEVICE_BORDER;
        final float by0 = halfHeight + DEVICE_BORDER;
        final float bx1 = halfWidth + DEVICE_BORDER;
        final float by1 = -halfHeight - DEVICE_BORDER;
        final float backZ = -DEVICE_THICKNESS;
        final float bodyDepthBoost = 0.020F;
        final float screenFaceZ = SCREEN_FACE_Z_OFFSET;
        final float screenTextureZ = SCREEN_TEXTURE_Z_OFFSET;
        final float flatBackZ = bodyDepthBoost + backZ;

        poseStack.pushPose();
        poseStack.scale(0.660F, 0.660F, 0.660F);
        float hoverX = PadFocusState.hoverX();
        float hoverY = PadFocusState.hoverY();
        poseStack.mulPose(Axis.XP.rotationDegrees(hoverY * HOVER_TILT_DEGREES * PadFocusState.progress(1.0F)));
        poseStack.mulPose(Axis.YP.rotationDegrees(hoverX * HOVER_TILT_DEGREES * PadFocusState.progress(1.0F)));
        publishProjectedQuad(poseStack.last().pose(), -halfWidth, halfHeight, screenTextureZ, halfWidth,
                -halfHeight, screenTextureZ);
        collector.submitCustomGeometry(poseStack, RenderTypes.itemCutout(bodyTextureId), (pose, buffer) -> {
            emitSolidQuad(buffer, pose, bx1, by0, flatBackZ, bx0, by0, flatBackZ, -halfWidth, halfHeight, flatBackZ,
                    halfWidth, halfHeight, flatBackZ, 0xFF222936);
            emitSolidQuad(buffer, pose, bx0, by1, flatBackZ, bx1, by1, flatBackZ, halfWidth, -halfHeight, flatBackZ,
                    -halfWidth, -halfHeight, flatBackZ, 0xFF181D27);
            emitSolidQuad(buffer, pose, bx0, by0, flatBackZ, -halfWidth, halfHeight, flatBackZ, -halfWidth,
                    -halfHeight, flatBackZ, bx0, by1, flatBackZ, 0xFF2A3342);
            emitSolidQuad(buffer, pose, bx1, by0, flatBackZ, halfWidth, halfHeight, flatBackZ, halfWidth,
                    -halfHeight, flatBackZ, bx1, by1, flatBackZ, 0xFF414B5F);
            emitSolidQuad(buffer, pose, bx0, by0, bodyDepthBoost, bx0, by1, bodyDepthBoost, bx1, by1,
                    bodyDepthBoost, bx1, by0, bodyDepthBoost, 0xFF05070B);
            emitSolidQuad(buffer, pose, bx0, by0, screenFaceZ, bx0, by1, screenFaceZ, bx1, by1, screenFaceZ, bx1,
                    by0, screenFaceZ, 0xFF050507);
            emitCameraDot(buffer, pose, bx0 + 0.050F, by0 - 0.022F, screenFaceZ + 0.003F);
            emitSideButton(buffer, pose, bx1 + 0.012F, halfHeight * 0.50F, 0.040F, 0.130F, 0xFF6B7588);
            emitSideButton(buffer, pose, bx1 + 0.012F, halfHeight * 0.22F, 0.040F, 0.130F, 0xFF5E687B);
        });
        if (videoUnderlay) {
            submitVideoLayer(poseStack, collector, deviceId, -halfWidth, halfHeight, halfWidth, -halfHeight,
                    VIDEO_UNDERLAY_Z_OFFSET, true, bodyTextureId);
        } else {
            logVideoSkip(deviceId, locked, "underlay-disabled");
        }
        collector.submitCustomGeometry(poseStack,
                videoUnderlay ? RenderTypes.itemTranslucent(textureId) : RenderTypes.itemCutout(textureId),
                (pose, buffer) -> {
                    emitTexturedQuad(buffer, pose, -halfWidth, halfHeight, screenTextureZ, -halfWidth, -halfHeight,
                            screenTextureZ, halfWidth, -halfHeight, screenTextureZ, halfWidth, halfHeight,
                            screenTextureZ,
                            true);
                });
        if (!videoUnderlay) {
            submitVideoLayer(poseStack, collector, deviceId, -halfWidth, halfHeight, halfWidth, -halfHeight,
                    VIDEO_TEXTURE_Z_OFFSET, locked, bodyTextureId);
        }
        if (VIDEO_RENDERDOC_PROBE && !hasVideoFrame(deviceId)) {
            submitRenderDocProbe(poseStack, collector, bodyTextureId, -halfWidth, halfHeight, halfWidth, -halfHeight,
                    VIDEO_TEXTURE_Z_OFFSET + 0.006F, 0x99FF00FF);
        }
        poseStack.popPose();
    }

    private static void markVideoSurfaceVisible(UUID deviceId) {
        if (deviceId != null && ClientMediaPlayback.hasPlayback(deviceId)) {
            MP4HandheldVideoClient.markVisible(deviceId);
        }
    }

    private static boolean hasVideoFrame(UUID deviceId) {
        return deviceId != null && ClientMediaPlayback.hasPlayback(deviceId)
                && MP4HandheldVideoClient.latestFrame(deviceId) != null;
    }

    private static void submitVideoLayer(PoseStack poseStack, SubmitNodeCollector collector, UUID deviceId,
            float x0, float y0, float x1, float y1, float z, boolean fullSurface, Identifier probeTextureId) {
        if (deviceId == null) {
            logVideoSkip(null, fullSurface, "missing-device-id");
            return;
        }
        if (!ClientMediaPlayback.hasPlayback(deviceId)) {
            logVideoSkip(deviceId, fullSurface, "no-local-playback");
            return;
        }
        if (MP4HandheldVideoClient.latestFrame(deviceId) == null) {
            logVideoSkip(deviceId, fullSurface, "no-latest-frame");
            return;
        }
        MP4HandheldVideoClient.markVisible(deviceId);
        float insetX = fullSurface ? 0.0F : (x1 - x0) * 0.025F;
        float insetY = fullSurface ? 0.0F : (y0 - y1) * 0.045F;
        float vx0 = x0 + insetX;
        float vy0 = y0 - insetY;
        float vx1 = x1 - insetX;
        float vy1 = y1 + insetY;
        boolean useRgbaFallback = IrisShaderpackCompat.isShaderPackInUse();
        MP4RgbaVideoLayer rgbaLayer = MP4RgbaVideoLayer.forHandheldDevice(deviceId);
        boolean rgba = useRgbaFallback && rgbaLayer.uploadLatest(deviceId);
        MP4Nv12VideoLayer nv12Layer = MP4Nv12VideoLayer.forHandheldDevice(deviceId);
        if (!rgba && (!nv12Layer.uploadLatest(deviceId) || nv12Layer.textureSet() == null)) {
            logVideoSkip(deviceId, fullSurface, "upload-failed");
            return;
        }
        logVideoSubmit(deviceId, fullSurface, rgba ? "rgba" : "nv12");
        collector.submitCustomGeometry(poseStack,
                rgba ? YuvVideoRenderTypes.padVideoRgbaEntity(rgbaLayer.textureId())
                        : YuvVideoRenderTypes.padNv12Entity(nv12Layer.textureSet().yId(), nv12Layer.textureSet().uId(),
                                nv12Layer.textureSet().vId()),
                (pose, buffer) -> emitTexturedQuad(buffer, pose, vx0, vy0, z, vx0, vy1, z, vx1, vy1, z, vx1,
                    vy0, z, false));
        if (VIDEO_RENDERDOC_PROBE) {
            submitRenderDocProbe(poseStack, collector, probeTextureId, vx0, vy0, vx1, vy1, z + 0.004F,
                    0x99FF00FF);
        }
    }

    private static void submitRenderDocProbe(PoseStack poseStack, SubmitNodeCollector collector,
            Identifier probeTextureId, float x0, float y0, float x1, float y1, float z, int color) {
        collector.submitCustomGeometry(poseStack, RenderTypes.itemTranslucent(probeTextureId),
                (pose, buffer) -> emitSolidQuad(buffer, pose, x0, y0, z, x0, y1, z, x1, y1, z, x1, y0, z, color));
    }

    private static void logVideoSkip(UUID deviceId, boolean fullSurface, String reason) {
        if (VIDEO_DEBUG_LOG) {
            LOGGER.info("Pad video layer skipped: reason={} device={} fullSurface={} hasPlayback={} hasFrame={}",
                    reason, deviceId, fullSurface, deviceId != null && ClientMediaPlayback.hasPlayback(deviceId),
                    deviceId != null && MP4HandheldVideoClient.latestFrame(deviceId) != null);
        }
    }

    private static void logVideoSubmit(UUID deviceId, boolean fullSurface, String mode) {
        if (VIDEO_DEBUG_LOG) {
            LOGGER.info("Pad video layer submitted: mode={} device={} fullSurface={}", mode, deviceId, fullSurface);
        }
    }

    private static void publishProjectedQuad(Matrix4f modelMatrix, float x0, float y0, float z0, float x1, float y1,
            float z1) {
        if (!PadFocusState.active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        var window = minecraft.getWindow();
        int physicalWidth = Math.max(1, window.getWidth());
        int physicalHeight = Math.max(1, window.getHeight());
        int guiWidth = Math.max(1, window.getGuiScaledWidth());
        int guiHeight = Math.max(1, window.getGuiScaledHeight());
        ScreenPoint topLeft = projectToGui(modelMatrix, x0, y0, z0, physicalWidth, physicalHeight, guiWidth,
                guiHeight);
        ScreenPoint bottomLeft = projectToGui(modelMatrix, x0, y1, z1, physicalWidth, physicalHeight, guiWidth,
                guiHeight);
        ScreenPoint bottomRight = projectToGui(modelMatrix, x1, y1, z1, physicalWidth, physicalHeight, guiWidth,
                guiHeight);
        ScreenPoint topRight = projectToGui(modelMatrix, x1, y0, z0, physicalWidth, physicalHeight, guiWidth,
                guiHeight);
        if (topLeft == null || bottomLeft == null || bottomRight == null || topRight == null) {
            return;
        }
        PadFocusState.updateProjectedQuad(topLeft.x(), topLeft.y(), topRight.x(), topRight.y(), bottomRight.x(),
                bottomRight.y(), bottomLeft.x(), bottomLeft.y(), guiWidth, guiHeight);
    }

    private static ScreenPoint projectToGui(Matrix4f modelMatrix, float x, float y, float z, int physicalWidth,
            int physicalHeight, int guiWidth, int guiHeight) {
        Matrix4f projection = firstPersonProjection(physicalWidth, physicalHeight);
        Vector4f clip = new Vector4f(x, y, z, 1.0F).mul(modelMatrix).mul(RenderSystem.getModelViewMatrix())
                .mul(projection);
        if (Math.abs(clip.w()) < 1.0E-5F) {
            return null;
        }
        float ndcX = clip.x() / clip.w();
        float ndcY = clip.y() / clip.w();
        float screenX = (ndcX * 0.5F + 0.5F) * physicalWidth;
        float screenY = (0.5F - ndcY * 0.5F) * physicalHeight;
        return new ScreenPoint(screenX * guiWidth / Math.max(1.0F, physicalWidth),
                screenY * guiHeight / Math.max(1.0F, physicalHeight));
    }

    private static Matrix4f firstPersonProjection(int physicalWidth, int physicalHeight) {
        float aspect = physicalWidth / (float) Math.max(1, physicalHeight);
        return new Matrix4f().perspective((float) Math.toRadians(70.0F), aspect, 0.05F,
                net.minecraft.client.renderer.GameRenderer.PROJECTION_3D_HUD_Z_FAR);
    }

    private record ScreenPoint(float x, float y) {
    }

    private static void emitCameraDot(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z) {
        float r = 0.010F;
        emitSolidQuad(buffer, pose, x - r, y + r, z, x - r, y - r, z, x + r, y - r, z, x + r, y + r, z,
                0xFF11151C);
    }

    private static void emitSideButton(VertexConsumer buffer, PoseStack.Pose pose, float x, float centerY,
            float width, float height, int color) {
        float sign = x > 0.0F ? 1.0F : -1.0F;
        float innerX = x - sign * width;
        float y0 = centerY + height * 0.5F;
        float y1 = centerY - height * 0.5F;
        emitSolidQuad(buffer, pose, innerX, y0, 0.026F, x, y0, 0.026F, x, y1, 0.026F, innerX, y1, 0.026F, color);
    }

    private static void emitTexturedQuad(VertexConsumer buffer, PoseStack.Pose pose,
            float p0x, float p0y, float p0z,
            float p1x, float p1y, float p1z,
            float p2x, float p2y, float p2z,
            float p3x, float p3y, float p3z,
            boolean flipV) {
        float v0 = flipV ? 1.0F : 0.0F;
        float v1 = flipV ? 0.0F : 1.0F;
        texturedVertex(buffer, pose, p0x, p0y, p0z, 0.0F, v0);
        texturedVertex(buffer, pose, p1x, p1y, p1z, 0.0F, v1);
        texturedVertex(buffer, pose, p2x, p2y, p2z, 1.0F, v1);
        texturedVertex(buffer, pose, p3x, p3y, p3z, 1.0F, v0);
        texturedVertex(buffer, pose, p3x, p3y, p3z, 1.0F, v0);
        texturedVertex(buffer, pose, p2x, p2y, p2z, 1.0F, v1);
        texturedVertex(buffer, pose, p1x, p1y, p1z, 0.0F, v1);
        texturedVertex(buffer, pose, p0x, p0y, p0z, 0.0F, v0);
    }

    private static void emitSolidQuad(VertexConsumer buffer, PoseStack.Pose pose,
            float ax, float ay, float az,
            float bx, float by, float bz,
            float cx, float cy, float cz,
            float dx, float dy, float dz,
            int color) {
        solidVertex(buffer, pose, ax, ay, az, color);
        solidVertex(buffer, pose, bx, by, bz, color);
        solidVertex(buffer, pose, cx, cy, cz, color);
        solidVertex(buffer, pose, dx, dy, dz, color);
    }

    private static void solidVertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, int color) {
        buffer.addVertex(pose, x, y, z)
                .setColor(color)
                .setUv(0.01F, 0.01F)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }

    private static void texturedVertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z,
            float u, float v) {
        RenderVertexUtils.texturedVertex(buffer, pose, x, y, z, u, v);
    }

    private static PadGuiTexture textureFor(UUID deviceId) {
        UUID key = deviceId != null ? deviceId : FALLBACK_DEVICE_ID;
        return GUI_TEXTURES.computeIfAbsent(key, id -> new PadGuiTexture(id.toString().replace('-', '_')));
    }
}