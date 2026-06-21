package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.mojang.blaze3d.ProjectionType;
import com.zhongbai233.net_music_can_play_bili.link.HolographicScreenSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

public final class HolographicPreviewPipRenderer extends PictureInPictureRenderer<HolographicPreviewPipRenderState> {
    private static final float SCENE_ORIGIN_Y_RATIO = 0.72F;
    private static final float SCREEN_FACE_EPSILON = 0.0025F;
    private static final float DEFAULT_PREVIEW_SCALE = HolographicScreenSettings.DEFAULT_PREVIEW_SCALE;
    private static final float ORBIT_FOV_DEGREES = HolographicScreenSettings.ORBIT_FOV_DEGREES;
    private static final float ORBIT_DEFAULT_CAMERA_DISTANCE = HolographicScreenSettings.ORBIT_DEFAULT_CAMERA_DISTANCE;
    private static final float ORBIT_TARGET_Y = HolographicScreenSettings.ORBIT_TARGET_Y;
    private static final float GIZMO_AXIS_WORLD_LEN = HolographicScreenSettings.GIZMO_AXIS_WORLD_LEN;
    private static final float GIZMO_ARROW_LEN = 0.12F;
    private static final float GIZMO_ARROW_WING = 0.065F;
    private static final float GIZMO_LABEL_SIZE = 0.055F;
    private static final int GIZMO_RING_SEGMENTS = 48;
    private static final float PLAYER_EYE_Y = 1.62F;
    private final ProjectionMatrixBuffer orbitProjectionBuffer = new ProjectionMatrixBuffer(
            "ncpb_holographic_orbit_projection");
    private final ProjectionMatrixBuffer firstPersonProjectionBuffer = new ProjectionMatrixBuffer(
            "ncpb_holographic_first_person_projection");

    public HolographicPreviewPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<HolographicPreviewPipRenderState> getRenderStateClass() {
        return HolographicPreviewPipRenderState.class;
    }

    @Override
    protected void renderToTexture(HolographicPreviewPipRenderState state, PoseStack poseStack) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);

        poseStack.pushPose();
        if (state.firstPerson()) {
            renderFirstPersonPreview(poseStack, state);
        } else {
            renderOrbitPreview(minecraft, poseStack, state);
            bufferSource.endBatch();
        }
        poseStack.popPose();
    }

    private void renderOrbitPreview(Minecraft minecraft, PoseStack poseStack, HolographicPreviewPipRenderState state) {
        int width = Math.max(1, state.x1() - state.x0());
        int height = Math.max(1, state.y1() - state.y0());
        float previewScale = state.scale() / Math.max(1.0F, Math.min(width, height)) * 200.0F;
        float cameraDistance = ORBIT_DEFAULT_CAMERA_DISTANCE * DEFAULT_PREVIEW_SCALE / Math.max(1.0F, previewScale);
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(ORBIT_FOV_DEGREES),
                (float) width / (float) height, 0.05F, 100.0F);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(orbitProjectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE);
        try {
            PoseStack orbitPoseStack = new PoseStack();
            orbitPoseStack.translate(0.0F, 0.0F, -cameraDistance);
            orbitPoseStack.mulPose(Axis.XP.rotationDegrees(state.previewPitch()));
            orbitPoseStack.mulPose(Axis.YP.rotationDegrees(state.previewYaw()));
            orbitPoseStack.translate(0.0F, -ORBIT_TARGET_Y, 0.0F);

            renderPlayer(minecraft, orbitPoseStack, state);
            bufferSource.endBatch();

            drawGrid(orbitPoseStack);
            if (state.playerGlowing()) {
                drawPlayerGlowOutline(orbitPoseStack, state);
            }
            drawHolographicScreens(orbitPoseStack, state);
            drawGizmo(orbitPoseStack, state);
            bufferSource.endBatch();
        } finally {
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private void renderFirstPersonPreview(PoseStack ignoredPipPoseStack, HolographicPreviewPipRenderState state) {
        int width = Math.max(1, state.x1() - state.x0());
        int height = Math.max(1, state.y1() - state.y0());
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(state.fovDegrees()),
                (float) width / (float) height, 0.05F, 100.0F);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(firstPersonProjectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE);
        try {
            PoseStack poseStack = new PoseStack();
            // 第一人称预览不是环绕相机：先按玩家眼睛位置移动世界，
            // 再把预览模型的前方（+Z）翻转到视图空间前方（-Z）。
            // 这样 screenDistance 在预览和真实眼镜视图中的含义保持一致。
            poseStack.translate(0.0F, 0.0F, -0.001F);
            poseStack.scale(1.0F, -1.0F, -1.0F);
            poseStack.translate(0.0F, -PLAYER_EYE_Y, 0.0F);
            drawHolographicScreensFrontOnly(poseStack, state);
            bufferSource.endBatch();
        } finally {
            RenderSystem.restoreProjectionMatrix();
        }
    }

    @Override
    public void close() {
        super.close();
        orbitProjectionBuffer.close();
        firstPersonProjectionBuffer.close();
    }

    private void renderPlayer(Minecraft minecraft, PoseStack poseStack, HolographicPreviewPipRenderState state) {
        FeatureRenderDispatcher featureDispatcher = minecraft.gameRenderer.getFeatureRenderDispatcher();
        SubmitNodeStorage nodeStorage = featureDispatcher.getSubmitNodeStorage();
        EntityRenderDispatcher entityDispatcher = minecraft.getEntityRenderDispatcher();
        CameraRenderState camera = new CameraRenderState();
        camera.orientation = new Quaternionf().rotateY((float) Math.PI);
        state.playerState().outlineColor = 0;

        poseStack.pushPose();
        poseStack.translate(state.playerTranslation().x, state.playerTranslation().y, state.playerTranslation().z);
        poseStack.scale(state.playerScale(), state.playerScale(), state.playerScale());
        entityDispatcher.submit(state.playerState(), camera, 0.0D, 0.0D, 0.0D, poseStack, nodeStorage);
        featureDispatcher.renderAllFeatures();
        poseStack.popPose();
    }

    private void drawGizmo(PoseStack poseStack, HolographicPreviewPipRenderState state) {
        int index = selectedIndex(state);
        poseStack.pushPose();
        poseStack.translate(screenOffsetX(state, index), 1.55F + screenOffsetY(state, index),
                screenDistance(state, index));
        if (state.localSpace()) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(screenRoll(state, index)));
        }

        PoseStack.Pose pose = poseStack.last();
        VertexConsumer buffer = bufferSource.getBuffer(RenderTypes.linesTranslucent());
        int handle = state.gizmoHandle();
        int tool = state.gizmoTool();

        boolean centerSelected = handle == 1;
        boolean xSelected = handle == 2;
        boolean ySelected = handle == 3;
        boolean zSelected = handle == 4;
        boolean ringSelected = handle == 5;

        line(buffer, pose, 0.0F, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F,
                xSelected ? 0xFFFF7777 : 0xE0FF4D4D, xSelected ? 2.4F : 1.5F);
        drawArrowHead(buffer, pose, 'x', xSelected ? 0xFFFF7777 : 0xE0FF4D4D, xSelected ? 2.4F : 1.5F);
        line(buffer, pose, 0.0F, 0.0F, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F,
                ySelected ? 0xFF77FF99 : 0xE04DFF72, ySelected ? 2.4F : 1.5F);
        drawArrowHead(buffer, pose, 'y', ySelected ? 0xFF77FF99 : 0xE04DFF72, ySelected ? 2.4F : 1.5F);
        line(buffer, pose, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN,
                zSelected ? 0xFF77B7FF : 0xE04DA3FF, zSelected ? 2.4F : 1.5F);
        drawArrowHead(buffer, pose, 'z', zSelected ? 0xFF77B7FF : 0xE04DA3FF, zSelected ? 2.4F : 1.5F);
        box(buffer, pose, -0.035F, -0.035F, -0.035F, 0.035F, 0.035F, 0.035F,
                centerSelected ? 0xFFFFFFFF : 0xFFE8E8E8, 1.4F);
        drawAxisLabel(buffer, pose, 'X', GIZMO_AXIS_WORLD_LEN + 0.16F, 0.0F, 0.0F, 0xFFFF7777);
        drawAxisLabel(buffer, pose, 'Y', 0.0F, GIZMO_AXIS_WORLD_LEN + 0.16F, 0.0F, 0xFF77FF99);
        drawAxisLabel(buffer, pose, tool == 2 ? 'S' : 'Z', 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN + 0.16F, 0xFF77B7FF);

        if (tool == 1) {
            drawGizmoRing(buffer, pose, ringSelected ? 0xFFFFD166 : 0xCCFFB347, ringSelected ? 2.0F : 1.2F);
        }
        if (tool == 2) {
            box(buffer, pose, GIZMO_AXIS_WORLD_LEN - 0.04F, -0.04F, -0.04F, GIZMO_AXIS_WORLD_LEN + 0.04F, 0.04F,
                    0.04F, 0xFFFF7777, 1.4F);
            box(buffer, pose, -0.04F, GIZMO_AXIS_WORLD_LEN - 0.04F, -0.04F, 0.04F, GIZMO_AXIS_WORLD_LEN + 0.04F,
                    0.04F, 0xFF77FF99, 1.4F);
            box(buffer, pose, -0.04F, -0.04F, GIZMO_AXIS_WORLD_LEN - 0.04F, 0.04F, 0.04F,
                    GIZMO_AXIS_WORLD_LEN + 0.04F, 0xFF77B7FF, 1.4F);
        }
        poseStack.popPose();
    }

    private static void drawArrowHead(VertexConsumer buffer, PoseStack.Pose pose, char axis, int color,
            float lineWidth) {
        if (axis == 'x') {
            line(buffer, pose, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN,
                    GIZMO_ARROW_WING, 0.0F, color, lineWidth);
            line(buffer, pose, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN,
                    -GIZMO_ARROW_WING, 0.0F, color, lineWidth);
            line(buffer, pose, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN,
                    0.0F, GIZMO_ARROW_WING, color, lineWidth);
            line(buffer, pose, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN,
                    0.0F, -GIZMO_ARROW_WING, color, lineWidth);
        } else if (axis == 'y') {
            line(buffer, pose, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, GIZMO_ARROW_WING,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, 0.0F, color, lineWidth);
            line(buffer, pose, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, -GIZMO_ARROW_WING,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, 0.0F, color, lineWidth);
            line(buffer, pose, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, GIZMO_ARROW_WING, color, lineWidth);
            line(buffer, pose, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, -GIZMO_ARROW_WING, color, lineWidth);
        } else {
            line(buffer, pose, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN, GIZMO_ARROW_WING, 0.0F,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, color, lineWidth);
            line(buffer, pose, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN, -GIZMO_ARROW_WING, 0.0F,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, color, lineWidth);
            line(buffer, pose, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, GIZMO_ARROW_WING,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, color, lineWidth);
            line(buffer, pose, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, -GIZMO_ARROW_WING,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, color, lineWidth);
        }
    }

    private static void drawAxisLabel(VertexConsumer buffer, PoseStack.Pose pose, char label, float x, float y, float z,
            int color) {
        if (label == 'X') {
            line(buffer, pose, x - GIZMO_LABEL_SIZE, y - GIZMO_LABEL_SIZE, z, x + GIZMO_LABEL_SIZE,
                    y + GIZMO_LABEL_SIZE, z, color, 1.7F);
            line(buffer, pose, x - GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x + GIZMO_LABEL_SIZE,
                    y - GIZMO_LABEL_SIZE, z, color, 1.7F);
        } else if (label == 'Y') {
            line(buffer, pose, x - GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x, y, z, color, 1.7F);
            line(buffer, pose, x + GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x, y, z, color, 1.7F);
            line(buffer, pose, x, y, z, x, y - GIZMO_LABEL_SIZE, z, color, 1.7F);
        } else if (label == 'S') {
            line(buffer, pose, x + GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x - GIZMO_LABEL_SIZE,
                    y + GIZMO_LABEL_SIZE, z, color, 1.7F);
            line(buffer, pose, x - GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x - GIZMO_LABEL_SIZE,
                    y, z, color, 1.7F);
            line(buffer, pose, x - GIZMO_LABEL_SIZE, y, z, x + GIZMO_LABEL_SIZE, y, z, color, 1.7F);
            line(buffer, pose, x + GIZMO_LABEL_SIZE, y, z, x + GIZMO_LABEL_SIZE,
                    y - GIZMO_LABEL_SIZE, z, color, 1.7F);
            line(buffer, pose, x + GIZMO_LABEL_SIZE, y - GIZMO_LABEL_SIZE, z, x - GIZMO_LABEL_SIZE,
                    y - GIZMO_LABEL_SIZE, z, color, 1.7F);
        } else {
            line(buffer, pose, x - GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x + GIZMO_LABEL_SIZE,
                    y + GIZMO_LABEL_SIZE, z, color, 1.7F);
            line(buffer, pose, x + GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x - GIZMO_LABEL_SIZE,
                    y - GIZMO_LABEL_SIZE, z, color, 1.7F);
            line(buffer, pose, x - GIZMO_LABEL_SIZE, y - GIZMO_LABEL_SIZE, z, x + GIZMO_LABEL_SIZE,
                    y - GIZMO_LABEL_SIZE, z, color, 1.7F);
        }
    }

    private static void drawGizmoRing(VertexConsumer buffer, PoseStack.Pose pose, int color, float lineWidth) {
        float radius = GIZMO_AXIS_WORLD_LEN * 0.66F;
        float prevX = radius;
        float prevY = 0.0F;
        for (int i = 1; i <= GIZMO_RING_SEGMENTS; i++) {
            float angle = (float) (Math.PI * 2.0D * i / GIZMO_RING_SEGMENTS);
            float x = (float) Math.cos(angle) * radius;
            float y = (float) Math.sin(angle) * radius;
            line(buffer, pose, prevX, prevY, 0.0F, x, y, 0.0F, color, lineWidth);
            prevX = x;
            prevY = y;
        }
    }

    private void drawPlayerGlowOutline(PoseStack poseStack, HolographicPreviewPipRenderState state) {
        poseStack.pushPose();
        poseStack.translate(state.playerTranslation().x, state.playerTranslation().y, state.playerTranslation().z);
        poseStack.scale(state.playerScale(), state.playerScale(), state.playerScale());

        float width = Math.max(0.58F, state.playerState().boundingBoxWidth) * 0.5F + 0.055F;
        float height = Math.max(1.8F, state.playerState().boundingBoxHeight) + 0.08F;
        float minY = -0.03F;
        float maxY = height;
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer buffer = bufferSource.getBuffer(RenderTypes.linesTranslucent());
        int outer = 0xF045E7FF;
        int inner = 0x9045E7FF;

        box(buffer, pose, -width, minY, -width, width, maxY, width, outer, 2.5F);
        box(buffer, pose, -width * 0.92F, minY + 0.03F, -width * 0.92F, width * 0.92F, maxY - 0.03F,
                width * 0.92F, inner, 1.2F);
        poseStack.popPose();
    }

    private void drawHolographicScreens(PoseStack poseStack, HolographicPreviewPipRenderState state) {
        int count = screenCount(state);
        for (int i = 0; i < count; i++) {
            drawHolographicScreen(poseStack, state, i, i == selectedIndex(state));
        }
    }

    private void drawHolographicScreen(PoseStack poseStack, HolographicPreviewPipRenderState state, int index,
            boolean selected) {
        poseStack.pushPose();
        poseStack.translate(screenOffsetX(state, index), 1.55F + screenOffsetY(state, index),
                screenDistance(state, index));
        poseStack.mulPose(Axis.ZP.rotationDegrees(screenRoll(state, index)));

        float halfH = screenHeight(state, index) * 0.5F;
        float halfW = halfH * screenAspect(state, index);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer edge = bufferSource.getBuffer(RenderTypes.linesTranslucent());
        int frontColor = selected ? 0xF045E7FF : 0x9845E7FF;
        int backColor = selected ? 0xCCFFB347 : 0x78FFB347;
        screenWire(edge, pose, -halfW, -halfH, halfW, halfH, -SCREEN_FACE_EPSILON, frontColor, selected ? 2.0F : 1.2F);
        screenWire(edge, pose, -halfW, -halfH, halfW, halfH, SCREEN_FACE_EPSILON, backColor, selected ? 1.4F : 0.9F);
        line(edge, pose, -halfW, -halfH, -SCREEN_FACE_EPSILON, halfW, halfH, -SCREEN_FACE_EPSILON, 0x6045E7FF,
                1.0F);
        line(edge, pose, -halfW, halfH, -SCREEN_FACE_EPSILON, halfW, -halfH, -SCREEN_FACE_EPSILON, 0x60FFB347,
                1.0F);
        poseStack.popPose();
    }

    private void drawHolographicScreensFrontOnly(PoseStack poseStack, HolographicPreviewPipRenderState state) {
        int count = screenCount(state);
        for (int i = 0; i < count; i++) {
            drawHolographicScreenFrontOnly(poseStack, state, i, i == selectedIndex(state));
        }
    }

    private void drawHolographicScreenFrontOnly(PoseStack poseStack, HolographicPreviewPipRenderState state, int index,
            boolean selected) {
        poseStack.pushPose();
        poseStack.translate(screenOffsetX(state, index), 1.55F - screenOffsetY(state, index),
                screenDistance(state, index));
        poseStack.mulPose(Axis.ZP.rotationDegrees(screenRoll(state, index)));

        float halfH = screenHeight(state, index) * 0.5F;
        float halfW = halfH * screenAspect(state, index);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer front = bufferSource.getBuffer(RenderTypes.debugQuads());
        emitQuad(front, pose, -halfW, -halfH, halfW, halfH, SCREEN_FACE_EPSILON, selected ? 0xD82BE7FF : 0x802BE7FF,
                false);
        poseStack.popPose();
    }

    private static int selectedIndex(HolographicPreviewPipRenderState state) {
        return Math.max(0, Math.min(screenCount(state) - 1, state.selectedScreen()));
    }

    private static int screenCount(HolographicPreviewPipRenderState state) {
        return Math.max(1, state.screenDistances() != null ? state.screenDistances().length : 0);
    }

    private static float screenDistance(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenDistances(), index, state.screenDistance());
    }

    private static float screenOffsetX(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenOffsetXs(), index, state.screenOffsetX());
    }

    private static float screenOffsetY(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenOffsetYs(), index, state.screenOffsetY());
    }

    private static float screenHeight(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenHeights(), index, state.screenHeight());
    }

    private static float screenAspect(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenAspects(), index, state.screenAspect());
    }

    private static float screenRoll(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenRolls(), index, state.screenRoll());
    }

    private static float valueAt(float[] values, int index, float fallback) {
        return values != null && index >= 0 && index < values.length ? values[index] : fallback;
    }

    private void drawGrid(PoseStack poseStack) {
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer buffer = bufferSource.getBuffer(RenderTypes.lines());
        for (int i = -4; i <= 4; i++) {
            line(buffer, pose, i * 0.5F, 0.0F, -2.5F, i * 0.5F, 0.0F, 3.5F, 0x4E5E4A1A);
        }
        for (int i = -5; i <= 7; i++) {
            float z = i * 0.5F;
            int alpha = z <= 0.0F ? 0x665E4A1A : 0x4A5E4A1A;
            line(buffer, pose, -2.0F, 0.0F, z, 2.0F, 0.0F, z, alpha);
        }
        line(buffer, pose, -2.0F, 0.01F, 0.0F, 2.0F, 0.01F, 0.0F, 0xAA45E7FF);
        line(buffer, pose, 0.0F, 0.01F, -2.5F, 0.0F, 0.01F, 3.5F, 0xAAFFB347);
    }

    private static void box(VertexConsumer buffer, PoseStack.Pose pose, float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ, int color, float lineWidth) {
        line(buffer, pose, minX, minY, minZ, maxX, minY, minZ, color, lineWidth);
        line(buffer, pose, maxX, minY, minZ, maxX, minY, maxZ, color, lineWidth);
        line(buffer, pose, maxX, minY, maxZ, minX, minY, maxZ, color, lineWidth);
        line(buffer, pose, minX, minY, maxZ, minX, minY, minZ, color, lineWidth);

        line(buffer, pose, minX, maxY, minZ, maxX, maxY, minZ, color, lineWidth);
        line(buffer, pose, maxX, maxY, minZ, maxX, maxY, maxZ, color, lineWidth);
        line(buffer, pose, maxX, maxY, maxZ, minX, maxY, maxZ, color, lineWidth);
        line(buffer, pose, minX, maxY, maxZ, minX, maxY, minZ, color, lineWidth);

        line(buffer, pose, minX, minY, minZ, minX, maxY, minZ, color, lineWidth);
        line(buffer, pose, maxX, minY, minZ, maxX, maxY, minZ, color, lineWidth);
        line(buffer, pose, maxX, minY, maxZ, maxX, maxY, maxZ, color, lineWidth);
        line(buffer, pose, minX, minY, maxZ, minX, maxY, maxZ, color, lineWidth);
    }

    private static void screenWire(VertexConsumer buffer, PoseStack.Pose pose, float minX, float minY, float maxX,
            float maxY, float z, int color, float lineWidth) {
        line(buffer, pose, minX, minY, z, maxX, minY, z, color, lineWidth);
        line(buffer, pose, maxX, minY, z, maxX, maxY, z, color, lineWidth);
        line(buffer, pose, maxX, maxY, z, minX, maxY, z, color, lineWidth);
        line(buffer, pose, minX, maxY, z, minX, minY, z, color, lineWidth);
    }

    private static void emitQuad(VertexConsumer buffer, PoseStack.Pose pose, float minX, float minY, float maxX,
            float maxY, float z, int color, boolean reverse) {
        if (reverse) {
            vertex(buffer, pose, minX, maxY, z, color);
            vertex(buffer, pose, maxX, maxY, z, color);
            vertex(buffer, pose, maxX, minY, z, color);
            vertex(buffer, pose, minX, minY, z, color);
        } else {
            vertex(buffer, pose, minX, minY, z, color);
            vertex(buffer, pose, maxX, minY, z, color);
            vertex(buffer, pose, maxX, maxY, z, color);
            vertex(buffer, pose, minX, maxY, z, color);
        }
    }

    private static void line(VertexConsumer buffer, PoseStack.Pose pose, float x1, float y1, float z1, float x2,
            float y2, float z2, int color) {
        line(buffer, pose, x1, y1, z1, x2, y2, z2, color, 1.0F);
    }

    private static void line(VertexConsumer buffer, PoseStack.Pose pose, float x1, float y1, float z1, float x2,
            float y2, float z2, int color, float lineWidth) {
        buffer.addVertex(pose, x1, y1, z1).setColor(color).setNormal(0.0F, 1.0F, 0.0F).setLineWidth(lineWidth);
        buffer.addVertex(pose, x2, y2, z2).setColor(color).setNormal(0.0F, 1.0F, 0.0F).setLineWidth(lineWidth);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, int color) {
        buffer.addVertex(pose, x, y, z).setColor(color).setNormal(0.0F, 0.0F, -1.0F);
    }

    @Override
    protected String getTextureLabel() {
        return "ncpb_holographic_preview";
    }

    @Override
    protected float getTranslateY(int textureHeight, int pixelScale) {
        return textureHeight * SCENE_ORIGIN_Y_RATIO;
    }
}