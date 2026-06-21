package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.mojang.blaze3d.systems.RenderSystem;
import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import com.zhongbai233.net_music_can_play_bili.client.MP4BiliLoginOverlay;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.RenderVertexUtils;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.YuvVideoRenderTypes;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** 通过 submitCustomGeometry 渲染的 MP4 贴图屏幕表面。 */
public final class MP4ItemScreenRenderer {
    private static final String PROJECTED_INPUT_PROPERTY = "netmusic.mp4.projected_input";
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final float HOVER_TILT_DEGREES = 4.0F;
    private static final float HOVER_HAND_FOLLOW_DEPTH = 0.055F;
    private static final float HELD_SURFACE_X = 0.53F;
    private static final float HELD_SURFACE_Y = 0.08F;
    private static final float HELD_SURFACE_Z = -1.08F;
    private static final float HELD_SURFACE_SCALE = 1.48F;
    private static final float FOCUSED_SURFACE_X = 0.50F;
    private static final float FOCUSED_SURFACE_Y = 0.10F;
    private static final float FOCUSED_SURFACE_Z = -1.09F;
    private static final float FOCUSED_SURFACE_SCALE = 1.92F;
    private static final float VIDEO_LAYER_OVERSCAN_PIXELS = 2.0F;
    private static final float DEVICE_BORDER = 0.035F;
    private static final float DEVICE_THICKNESS = 0.055F;
    private static final float SCREEN_FACE_Z_OFFSET = 0.020F;
    private static final float SCREEN_TEXTURE_Z_OFFSET = 0.022F;
    private static final UUID FALLBACK_DEVICE_ID = new UUID(0L, 0L);
    private static final Map<UUID, MP4GuiTexture> GUI_TEXTURES = new ConcurrentHashMap<>();

    private MP4ItemScreenRenderer() {
    }

    public static void warmup() {
        textureFor(null).warmup();
    }

    public static void releaseAll() {
        GUI_TEXTURES.values().forEach(texture -> texture.close());
        GUI_TEXTURES.clear();
        MP4Nv12VideoLayer.releaseAll();
        MP4RgbaVideoLayer.releaseAll();
    }

    public static void releaseVideoLayers(UUID deviceId) {
        MP4Nv12VideoLayer.release(deviceId);
        MP4RgbaVideoLayer.release(deviceId);
    }

    public static void releaseAllVideoLayers() {
        MP4Nv12VideoLayer.releaseAll();
        MP4RgbaVideoLayer.releaseAll();
    }

        public static void renderHeldOffscreenGuiFrameStart() {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.player == null) {
                        return;
                }
                renderHeldOffscreenGuiFrameStart(minecraft.player.getMainHandItem());
                renderHeldOffscreenGuiFrameStart(minecraft.player.getOffhandItem());
        }

        private static void renderHeldOffscreenGuiFrameStart(ItemStack stack) {
                if (!(stack.getItem() instanceof MP4Item)) {
                        return;
                }
                UUID deviceId = MP4Item.readDeviceId(stack);
                if (deviceId == null) {
                        return;
                }
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

        UUID deviceId = stack.getItem() instanceof MP4Item ? MP4Item.readDeviceId(stack) : null;
        if (!MP4FocusState.activeFor(hand) && stack.getItem() instanceof MP4Item) {
            MP4FocusState.loadForHeldRender(MP4Client.stateForHeldRender(stack));
            MP4FocusState.loadQueue(MP4Client.cachedQueueFor(stack));
        }

        poseStack.pushPose();
        if (MP4FocusState.activeFor(hand)) {
            applyFocusedMapPose(partialTick, poseStack);
            renderFocusedHand(player, partialTick, poseStack, collector, light, arm, armRenderer);
        } else {
            applyOneHandedMapPose(arm, swingProgress, equipProgress, poseStack, collector, light, armRenderer);
        }
        submitTexturedSurface(poseStack, collector, partialTick, deviceId);
        poseStack.popPose();
    }

    private static void renderFocusedHand(AbstractClientPlayer player, float partialTick, PoseStack poseStack,
            SubmitNodeCollector collector, int light, HumanoidArm arm, ArmRenderer armRenderer) {
        if (player.isInvisible()) {
            return;
        }
        poseStack.pushPose();
        float side = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        float p = MP4FocusState.progress(partialTick);
        poseStack.translate(side * -0.085F, -0.083F, 0.255F + focusedHandFollowDepth() * p);
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * 3.0F));
        armRenderer.renderPlayerArm(poseStack, collector, light, 0.0F, 0.0F, arm);
        poseStack.popPose();
    }

    private static void applyFocusedMapPose(float partialTick, PoseStack poseStack) {
        float p = MP4FocusState.progress(partialTick);
        float eased = 1.0F - (1.0F - p) * (1.0F - p);
        poseStack.translate(lerp(HELD_SURFACE_X, FOCUSED_SURFACE_X, eased),
                lerp(HELD_SURFACE_Y, FOCUSED_SURFACE_Y, eased),
                lerp(HELD_SURFACE_Z, FOCUSED_SURFACE_Z, eased));
        float scale = lerp(HELD_SURFACE_SCALE, FOCUSED_SURFACE_SCALE, eased);
        poseStack.scale(scale, scale, scale);
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

    private static float lerp(float start, float end, float t) {
        return start + (end - start) * t;
    }

    private static void submitTexturedSurface(PoseStack poseStack, SubmitNodeCollector collector, float partialTick,
            UUID deviceId) {
        MP4FocusState.renderTick();
        MP4GuiTexture guiTexture = textureFor(deviceId);
        Identifier textureId = guiTexture.textureId(deviceId);
        boolean flipGuiV = true;
        Identifier bodyTextureId = guiTexture.whiteTextureId();
        float logicalWidth = MP4GuiTexture.WIDTH;
        float logicalHeight = MP4GuiTexture.HEIGHT;
        float aspect = logicalWidth / logicalHeight;
        float halfHeight = 0.50F;
        float halfWidth = halfHeight * aspect;

        float hoverX = MP4FocusState.hoverX() * guiHoverFlip(partialTick);
        float hoverY = MP4FocusState.hoverY() * guiHoverFlip(partialTick);

        final float bx0 = -halfWidth - DEVICE_BORDER;
        final float by0 = halfHeight + DEVICE_BORDER;
        final float bx1 = halfWidth + DEVICE_BORDER;
        final float by1 = -halfHeight - DEVICE_BORDER;
        final float backZ = -DEVICE_THICKNESS;

        final float bodyDepthBoost = 0.020F;
        final float p0z = 0.0F;
        final float p1z = 0.0F;
        final float p2z = 0.0F;
        final float p3z = 0.0F;
        final float screenFaceZ = SCREEN_FACE_Z_OFFSET;
        final float screenTextureZ = SCREEN_TEXTURE_Z_OFFSET;
        final float b0z = p0z + bodyDepthBoost;
        final float b1z = p1z + bodyDepthBoost;
        final float b2z = p2z + bodyDepthBoost;
        final float b3z = p3z + bodyDepthBoost;
        final float flatBackZ = bodyDepthBoost + backZ;
        final float bb0z = flatBackZ;
        final float bb1z = flatBackZ;
        final float bb2z = flatBackZ;
        final float bb3z = flatBackZ;

        poseStack.pushPose();
        poseStack.scale(0.532F, 0.532F, 0.532F);
        float landscapeShift = MP4FocusState.landscapeTransformProgress(partialTick)
                * MP4FocusState.LANDSCAPE_LEFT_SHIFT_FRACTION;
        poseStack.translate(-halfHeight * 2.0F * landscapeShift, 0.0F, 0.0F);
        float pivotX = lerp(-halfWidth, halfWidth, MP4FocusState.ROTATION_PIVOT_U);
        float pivotY = lerp(halfHeight, -halfHeight, MP4FocusState.ROTATION_PIVOT_V);
        poseStack.translate(pivotX, pivotY, 0.0F);
        poseStack.mulPose(Axis.ZP.rotationDegrees(deviceRotationDegrees(partialTick)));
        poseStack.mulPose(Axis.XP.rotationDegrees(hoverY * HOVER_TILT_DEGREES * MP4FocusState.progress(1.0F)));
        poseStack.mulPose(Axis.YP.rotationDegrees(hoverX * HOVER_TILT_DEGREES * MP4FocusState.progress(1.0F)));
        poseStack.translate(-pivotX, -pivotY, 0.0F);
        publishProjectedQuad(poseStack.last().pose(), bx0, by0, b0z, bx1, by1, b1z, b2z, b3z);
        collector.submitCustomGeometry(
                poseStack,
                RenderTypes.itemCutout(bodyTextureId),
                (pose, buffer) -> {
                    emitSolidQuad(buffer, pose, bx1, by0, bb3z, bx0, by0, bb0z, -halfWidth, halfHeight, flatBackZ,
                            halfWidth, halfHeight, flatBackZ, 0xFF202531);
                    emitSolidQuad(buffer, pose, bx0, by1, bb1z, bx1, by1, bb2z, halfWidth, -halfHeight,
                            flatBackZ, -halfWidth, -halfHeight, flatBackZ, 0xFF181C25);
                    emitSolidQuad(buffer, pose, bx0, by0, bb0z, -halfWidth, halfHeight, flatBackZ, -halfWidth,
                            -halfHeight, flatBackZ, bx0, by1, bb1z, 0xFF242A36);
                    emitSolidQuad(buffer, pose, bx1, by0, bb3z, halfWidth, halfHeight, flatBackZ, halfWidth,
                            -halfHeight, flatBackZ, bx1, by1, bb2z, 0xFF3B4354);
                    emitSolidQuad(buffer, pose, bx0, by1, b1z, bx0, by0, b0z, bx0, by0, bb0z, bx0, by1, bb1z,
                            0xFF242A36);
                    emitSolidQuad(buffer, pose, bx1, by0, b3z, bx1, by1, b2z, bx1, by1, bb2z, bx1, by0, bb3z,
                            0xFF3B4354);
                    emitSolidQuad(buffer, pose, bx1, by0, b3z, bx0, by0, b0z, bx0, by0, bb0z, bx1, by0, bb3z,
                            0xFF475062);
                    emitSolidQuad(buffer, pose, bx0, by1, b1z, bx1, by1, b2z, bx1, by1, bb2z, bx0, by1, bb1z,
                            0xFF181C25);
                    emitSolidQuad(buffer, pose, bx0, by0, screenFaceZ, bx0, by1, screenFaceZ, bx1, by1,
                            screenFaceZ, bx1, by0, screenFaceZ, 0xFF050507);
                    emitSideButton(buffer, pose, bx1 + 0.012F, halfHeight * 0.78F, halfHeight, 0.038F, 0.165F,
                            halfWidth, 0xFF687285);
                    emitSideButton(buffer, pose, bx0 - 0.012F, halfHeight * 0.74F, halfHeight, 0.038F, 0.145F,
                            halfWidth, 0xFF5D697D);
                    emitSideButton(buffer, pose, bx0 - 0.012F, halfHeight * 0.52F, halfHeight, 0.038F, 0.145F,
                            halfWidth, 0xFF5D697D);
                });
        submitNv12VideoLayer(poseStack, collector, deviceId, bx0, by0, bx1, by1, b0z, b1z, b2z, b3z);
        boolean splitGuiAroundVideo = MP4FocusState.visualLandscape(1.0F) && MP4FocusState.videoEnabled()
                && MP4FocusState.playing() && MP4HandheldVideoClient.latestFrame(deviceId) != null
                && !MP4BiliLoginOverlay.visible();
        collector.submitCustomGeometry(
                poseStack,
                splitGuiAroundVideo ? RenderTypes.itemTranslucent(textureId) : RenderTypes.itemCutout(textureId),
                (pose, buffer) -> {
                    if (splitGuiAroundVideo) {
                        if (MP4FocusState.controlsVisible()) {
                            emitLandscapeGuiRect(buffer, pose, bx0, by0, bx1, by1, screenTextureZ, flipGuiV,
                                    0.0F, 0.0F, 448.0F, 10.0F);
                            emitLandscapeGuiRect(buffer, pose, bx0, by0, bx1, by1, screenTextureZ, flipGuiV,
                                    0.0F, 246.0F, 448.0F, 256.0F);
                            emitLandscapeGuiRect(buffer, pose, bx0, by0, bx1, by1, screenTextureZ, flipGuiV,
                                    0.0F, 10.0F, 10.0F, 246.0F);
                            emitLandscapeGuiRect(buffer, pose, bx0, by0, bx1, by1, screenTextureZ, flipGuiV,
                                    438.0F, 10.0F, 448.0F, 246.0F);
                            emitLandscapeGuiRect(buffer, pose, bx0, by0, bx1, by1, screenTextureZ, flipGuiV,
                                    14.0F, 14.0F, 434.0F, 40.0F);
                            emitLandscapeGuiRect(buffer, pose, bx0, by0, bx1, by1, screenTextureZ, flipGuiV,
                                    16.0F, 171.0F, 432.0F, 237.0F);
                        } else {
                            String resolutionLabel = MP4HandheldVideoClient.currentResolutionLabel(deviceId);
                            int resolutionWidth = textWidth(
                                    resolutionLabel.isBlank() ? MP4FocusState.quality() : resolutionLabel);
                            emitLandscapeGuiRect(buffer, pose, bx0, by0, bx1, by1, screenTextureZ, flipGuiV,
                                    360.0F, 16.0F, Math.min(438.0F, 360.0F + resolutionWidth + 4.0F), 30.0F);
                        }
                        String subtitle = MP4HandheldVideoClient.currentSubtitle(deviceId);
                        if (MP4FocusState.lyricsEnabled() && !subtitle.isBlank()) {
                            int subtitleWidth = Math.min(420, Math.max(36, textWidth(subtitle) + 18));
                            float subtitleLeft = 224.0F - subtitleWidth / 2.0F;
                            float subtitleY = MP4FocusState.controlsVisible() ? 148.0F : 196.0F;
                            emitLandscapeGuiRect(buffer, pose, bx0, by0, bx1, by1, screenTextureZ, flipGuiV,
                                    Math.max(10.0F, subtitleLeft), subtitleY,
                                    Math.min(438.0F, subtitleLeft + subtitleWidth), subtitleY + 17.0F);
                        }
                        if (MP4FocusState.playlistOpen()) {
                            emitLandscapeGuiRect(buffer, pose, bx0, by0, bx1, by1, screenTextureZ, flipGuiV,
                                    72.0F, 44.0F, 376.0F, 120.0F);
                        }
                        if (MP4FocusState.subtitleMenuOpen() && MP4FocusState.controlsVisible()) {
                            emitLandscapeGuiRect(buffer, pose, bx0, by0, bx1, by1, screenTextureZ, flipGuiV,
                                    232.0F, 48.0F, 340.0F, 162.0F);
                        }
                        if (MP4FocusState.qualityMenuOpen() && MP4FocusState.controlsVisible()) {
                            emitLandscapeGuiRect(buffer, pose, bx0, by0, bx1, by1, screenTextureZ, flipGuiV,
                                    318.0F, 42.0F, 412.0F, 174.0F);
                        }
                    } else {
                        emitGuiRect(buffer, pose, bx0, by0, bx1, by1, screenTextureZ, flipGuiV,
                                0.0F, 0.0F, MP4GuiTexture.WIDTH, MP4GuiTexture.HEIGHT);
                    }
                });
        poseStack.popPose();
    }

    private static void publishProjectedQuad(Matrix4f modelMatrix, float bx0, float by0, float b0z, float bx1,
            float by1, float b1z, float b2z, float b3z) {
        if (!MP4FocusState.active() || !projectedInputEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        var window = minecraft.getWindow();
        int physicalWidth = Math.max(1, window.getWidth());
        int physicalHeight = Math.max(1, window.getHeight());
        int guiWidth = Math.max(1, minecraft.getWindow().getGuiScaledWidth());
        int guiHeight = Math.max(1, minecraft.getWindow().getGuiScaledHeight());
        ScreenPoint topLeft = projectToGui(modelMatrix, bx0, by0, b0z, physicalWidth, physicalHeight, guiWidth,
                guiHeight);
        ScreenPoint bottomLeft = projectToGui(modelMatrix, bx0, by1, b1z, physicalWidth, physicalHeight, guiWidth,
                guiHeight);
        ScreenPoint bottomRight = projectToGui(modelMatrix, bx1, by1, b2z, physicalWidth, physicalHeight, guiWidth,
                guiHeight);
        ScreenPoint topRight = projectToGui(modelMatrix, bx1, by0, b3z, physicalWidth, physicalHeight, guiWidth,
                guiHeight);
        if (topLeft == null || bottomLeft == null || bottomRight == null || topRight == null) {
            return;
        }
        MP4FocusState.updateProjectedQuad(topLeft.x(), topLeft.y(), topRight.x(), topRight.y(), bottomRight.x(),
                bottomRight.y(), bottomLeft.x(), bottomLeft.y(), guiWidth, guiHeight, MP4FocusState.landscape());
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

    private record ScreenPoint(float x, float y) {
    }

    private static boolean projectedInputEnabled() {
        return Boolean.parseBoolean(System.getProperty(PROJECTED_INPUT_PROPERTY, "true"));
    }

    private static Matrix4f firstPersonProjection(int physicalWidth, int physicalHeight) {
        float aspect = physicalWidth / (float) Math.max(1, physicalHeight);
        return new Matrix4f().perspective((float) Math.toRadians(70.0F), aspect, 0.05F,
                net.minecraft.client.renderer.GameRenderer.PROJECTION_3D_HUD_Z_FAR);
    }

    private static void submitNv12VideoLayer(PoseStack poseStack, SubmitNodeCollector collector, UUID deviceId,
            float bx0, float by0, float bx1, float by1, float b0z, float b1z, float b2z, float b3z) {
        if (deviceId == null) {
            return;
        }
        if (!MP4FocusState.visualLandscape(1.0F) || !MP4FocusState.videoEnabled() || !MP4FocusState.playing()) {
            return;
        }
        float inset = 10.0F - VIDEO_LAYER_OVERSCAN_PIXELS;
        float right = MP4GuiTexture.HEIGHT - 10.0F + VIDEO_LAYER_OVERSCAN_PIXELS;
        float bottom = MP4GuiTexture.WIDTH - 10.0F + VIDEO_LAYER_OVERSCAN_PIXELS;
        SurfacePoint topLeft = landscapeSurfacePoint(inset, inset, bx0, by0, bx1, by1, b0z, b1z, b2z,
                b3z);
        SurfacePoint bottomLeft = landscapeSurfacePoint(inset, bottom, bx0, by0, bx1,
                by1, b0z, b1z, b2z, b3z);
        SurfacePoint bottomRight = landscapeSurfacePoint(right, bottom,
                bx0, by0, bx1, by1, b0z, b1z, b2z, b3z);
        SurfacePoint topRight = landscapeSurfacePoint(right, inset, bx0, by0, bx1, by1,
                b0z, b1z, b2z, b3z);
        MP4HandheldVideoClient.markVisible(deviceId);
        boolean useRgbaFallback = IrisShaderpackCompat.isShaderPackInUse();
        MP4RgbaVideoLayer rgbaLayer = MP4RgbaVideoLayer.forDevice(deviceId);
        boolean rgba = useRgbaFallback && rgbaLayer.uploadLatest(deviceId);
        MP4Nv12VideoLayer layer = MP4Nv12VideoLayer.forDevice(deviceId);
        if (!rgba && (!layer.uploadLatest(deviceId) || layer.textureSet() == null)) {
            return;
        }
        collector.submitCustomGeometry(
                poseStack,
                rgba ? RenderTypes.itemCutout(rgbaLayer.textureId())
                        : YuvVideoRenderTypes.nv12Entity(layer.textureSet().yId(), layer.textureSet().uId(),
                                layer.textureSet().vId()),
                (pose, buffer) -> {
                    emitQuad(buffer, pose, topLeft.x(), topLeft.y(), topLeft.z(), bottomLeft.x(), bottomLeft.y(),
                            bottomLeft.z(), bottomRight.x(), bottomRight.y(), bottomRight.z(), topRight.x(),
                            topRight.y(), topRight.z(), false);
                    emitQuad(buffer, pose, topLeft.x(), topLeft.y(), topLeft.z(), bottomLeft.x(), bottomLeft.y(),
                            bottomLeft.z(), bottomRight.x(), bottomRight.y(), bottomRight.z(), topRight.x(),
                            topRight.y(), topRight.z(), true);
                });
    }

    private static SurfacePoint landscapeSurfacePoint(float landscapeX, float landscapeY, float bx0, float by0,
            float bx1, float by1, float b0z, float b1z, float b2z, float b3z) {
        float u = Math.max(0.0F, Math.min(1.0F, landscapeY / MP4GuiTexture.WIDTH));
        float v = Math.max(0.0F, Math.min(1.0F, (MP4GuiTexture.HEIGHT - 1.0F - landscapeX) / MP4GuiTexture.HEIGHT));
        float x = lerp(bx0, bx1, u);
        float y = lerp(by0, by1, v);
        float leftZ = lerp(b0z, b1z, v);
        float rightZ = lerp(b3z, b2z, v);
        return new SurfacePoint(x, y, lerp(leftZ, rightZ, u) + 0.001F);
    }

    private record SurfacePoint(float x, float y, float z) {
    }

    private static float deviceRotationDegrees(float partialTick) {
        return MP4FocusState.deviceRotationDegrees(partialTick);
    }

    private static float focusedHandFollowDepth() {
        float depth = MP4FocusState.progress(1.0F) * HOVER_HAND_FOLLOW_DEPTH;
        float hoverX = MP4FocusState.hoverX() * handHoverXFlip(1.0F);
        float hoverY = MP4FocusState.hoverY() * handHoverYFlip(1.0F);
        return (-hoverX * 0.85F - hoverY * 0.65F) * depth;
    }

    private static float guiHoverFlip(float partialTick) {
        return 1.0F;
    }

    private static float handHoverXFlip(float partialTick) {
        return 1.0F;
    }

    private static float handHoverYFlip(float partialTick) {
        return lerp(1.0F, -1.0F, MP4FocusState.landscapeTransformProgress(partialTick));
    }

    private static int textWidth(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft != null ? minecraft.font : null;
        return font != null && text != null ? font.width(text) : 0;
    }

    private static MP4GuiTexture textureFor(UUID deviceId) {
        UUID key = deviceId != null ? deviceId : FALLBACK_DEVICE_ID;
        return GUI_TEXTURES.computeIfAbsent(key, id -> new MP4GuiTexture(id.toString().replace('-', '_')));
    }

    private static void emitQuad(VertexConsumer buffer, PoseStack.Pose pose,
            float p0x, float p0y, float p0z,
            float p1x, float p1y, float p1z,
            float p2x, float p2y, float p2z,
            float p3x, float p3y, float p3z,
            boolean reverse) {
        emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z, reverse, false);
    }

    private static void emitQuad(VertexConsumer buffer, PoseStack.Pose pose,
            float p0x, float p0y, float p0z,
            float p1x, float p1y, float p1z,
            float p2x, float p2y, float p2z,
            float p3x, float p3y, float p3z,
            boolean reverse, boolean flipV) {
        float v0 = flipV ? 1.0F : 0.0F;
        float v1 = flipV ? 0.0F : 1.0F;
        if (reverse) {
            vertex(buffer, pose, p3x, p3y, p3z, 1.0F, v0);
            vertex(buffer, pose, p2x, p2y, p2z, 1.0F, v1);
            vertex(buffer, pose, p1x, p1y, p1z, 0.0F, v1);
            vertex(buffer, pose, p0x, p0y, p0z, 0.0F, v0);
        } else {
            vertex(buffer, pose, p0x, p0y, p0z, 0.0F, v0);
            vertex(buffer, pose, p1x, p1y, p1z, 0.0F, v1);
            vertex(buffer, pose, p2x, p2y, p2z, 1.0F, v1);
            vertex(buffer, pose, p3x, p3y, p3z, 1.0F, v0);
        }
    }

    private static void emitLandscapeGuiRect(VertexConsumer buffer, PoseStack.Pose pose,
            float bx0, float by0, float bx1, float by1, float z, boolean flipV,
            float x0, float y0, float x1, float y1) {
        // Offscreen landscape GUI is drawn by rotating the normal 256x448 texture
        // space:
        // landscape(x,y) -> portrait(u=x? no) actually samples from portrait pixel
        // px = y, py = HEIGHT - x. Keep the same mapping here so clipped GUI fragments
        // line up exactly with MP4OffscreenGuiRenderer.withLandscape().
        emitGuiRect(buffer, pose, bx0, by0, bx1, by1, z, flipV,
                y0, MP4GuiTexture.HEIGHT - x1, y1, MP4GuiTexture.HEIGHT - x0);
    }

    private static void emitGuiRect(VertexConsumer buffer, PoseStack.Pose pose,
            float bx0, float by0, float bx1, float by1, float z, boolean flipV,
            float px0, float py0, float px1, float py1) {
        float left = lerp(bx0, bx1, px0 / MP4GuiTexture.WIDTH);
        float right = lerp(bx0, bx1, px1 / MP4GuiTexture.WIDTH);
        float top = lerp(by0, by1, py0 / MP4GuiTexture.HEIGHT);
        float bottom = lerp(by0, by1, py1 / MP4GuiTexture.HEIGHT);
        float u0 = px0 / MP4GuiTexture.WIDTH;
        float u1 = px1 / MP4GuiTexture.WIDTH;
        float rawV0 = py0 / MP4GuiTexture.HEIGHT;
        float rawV1 = py1 / MP4GuiTexture.HEIGHT;
        float vTop = flipV ? 1.0F - rawV0 : rawV0;
        float vBottom = flipV ? 1.0F - rawV1 : rawV1;
        vertex(buffer, pose, left, top, z, u0, vTop);
        vertex(buffer, pose, left, bottom, z, u0, vBottom);
        vertex(buffer, pose, right, bottom, z, u1, vBottom);
        vertex(buffer, pose, right, top, z, u1, vTop);
        vertex(buffer, pose, right, top, z, u1, vTop);
        vertex(buffer, pose, right, bottom, z, u1, vBottom);
        vertex(buffer, pose, left, bottom, z, u0, vBottom);
        vertex(buffer, pose, left, top, z, u0, vTop);
    }

    private static void emitSideButton(VertexConsumer buffer, PoseStack.Pose pose, float x, float centerY,
            float halfHeight, float width, float height, float halfWidth, int color) {
        float sign = x > 0.0F ? 1.0F : -1.0F;
        float outerX = x;
        float innerX = x - sign * width;
        float y0 = centerY + height * 0.5F;
        float y1 = centerY - height * 0.5F;
        float frontInnerTop = 0.010F;
        float frontOuterTop = 0.010F;
        float frontOuterBottom = 0.010F;
        float frontInnerBottom = 0.010F;
        float backInnerTop = frontInnerTop - DEVICE_THICKNESS * 0.72F;
        float backOuterTop = frontOuterTop - DEVICE_THICKNESS * 0.72F;
        float backOuterBottom = frontOuterBottom - DEVICE_THICKNESS * 0.72F;
        float backInnerBottom = frontInnerBottom - DEVICE_THICKNESS * 0.72F;
        int sideColor = darker(color);
        int capColor = darker(color, 0.76F);
        int backColor = darker(color, 0.48F);

        emitSolidQuad(buffer, pose, innerX, y0, frontInnerTop, outerX, y0, frontOuterTop, outerX, y1,
                frontOuterBottom, innerX, y1, frontInnerBottom, color);
        emitSolidQuad(buffer, pose, outerX, y0, frontOuterTop, outerX, y0, backOuterTop, outerX, y1,
                backOuterBottom, outerX, y1, frontOuterBottom, sideColor);
        emitSolidQuad(buffer, pose, innerX, y0, backInnerTop, innerX, y0, frontInnerTop, innerX, y1,
                frontInnerBottom, innerX, y1, backInnerBottom, sideColor);
        emitSolidQuad(buffer, pose, innerX, y0, backInnerTop, outerX, y0, backOuterTop, outerX, y0,
                frontOuterTop, innerX, y0, frontInnerTop, capColor);
        emitSolidQuad(buffer, pose, innerX, y1, frontInnerBottom, outerX, y1, frontOuterBottom, outerX, y1,
                backOuterBottom, innerX, y1, backInnerBottom, capColor);
        emitSolidQuad(buffer, pose, outerX, y0, backOuterTop, innerX, y0, backInnerTop, innerX, y1,
                backInnerBottom, outerX, y1, backOuterBottom, backColor);
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

    private static int darker(int color) {
        return darker(color, 0.58F);
    }

    private static int darker(int color, float scale) {
        int a = color & 0xFF000000;
        int r = Math.round(((color >> 16) & 0xFF) * scale);
        int g = Math.round(((color >> 8) & 0xFF) * scale);
        int b = Math.round((color & 0xFF) * scale);
        return a | (r << 16) | (g << 8) | b;
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float u,
            float v) {
        RenderVertexUtils.texturedVertex(buffer, pose, x, y, z, u, v);
    }

}
