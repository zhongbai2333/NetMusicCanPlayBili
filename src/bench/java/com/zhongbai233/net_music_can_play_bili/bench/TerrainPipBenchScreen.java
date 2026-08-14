package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewFrame;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.client.renderer.gui.HolographicPreviewPipRenderState;
import com.zhongbai233.net_music_can_play_bili.mixin.GuiGraphicsExtractorAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;

final class TerrainPipBenchScreen extends net.minecraft.client.gui.screens.Screen {
    private static final float[] NO_FLOATS = new float[0];
    private static final int[] NO_INTS = new int[0];
    private final BlockPos origin;
    private double angleDegrees = 35.0D;
    private boolean renderTerrain = true;

    TerrainPipBenchScreen(BlockPos origin) {
        super(net.minecraft.network.chat.Component.literal("Terrain material PIP bench"));
        this.origin = origin.immutable();
    }

    void setAngleDegrees(double angleDegrees) {
        this.angleDegrees = angleDegrees;
    }

    void setRenderTerrain(boolean renderTerrain) {
        this.renderTerrain = renderTerrain;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF080B10);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int x = 16;
        int y = 16;
        int w = Math.max(1, width - 32);
        int h = Math.max(1, height - 32);
        ScreenRectangle viewportBounds = new ScreenRectangle(x, y, w, h);
        var viewport = new com.zhongbai233.scene_editor.core.projection.EditorViewport(
                x, y, w, h);
        double radians = Math.toRadians(angleDegrees);
        org.joml.Vector3d focus = new org.joml.Vector3d(18.0D, 4.0D, 0.0D);
        org.joml.Vector3d camera = new org.joml.Vector3d(
                focus.x + Math.cos(radians) * 105.0D, 58.0D,
                focus.z + Math.sin(radians) * 105.0D);
        var cameraState = com.zhongbai233.scene_editor.core.camera.EditorCameraState
                .lookingAt(com.zhongbai233.scene_editor.core.camera.EditorCameraMode.ORBIT,
                        camera, focus, new org.joml.Vector3d(0.0D, 1.0D, 0.0D),
                        70.0F, 1.0F, 0.05F, 512.0F);
        var cameraFrame = new com.zhongbai233.scene_editor.core.camera.CameraFrame(
                com.zhongbai233.scene_editor.core.camera.CameraMatrices.create(
                        cameraState, viewport), viewport, cameraState.mode());
        var guiState = ((GuiGraphicsExtractorAccessor) graphics).net_music_can_play_bili$guiRenderState();
        guiState.addPicturesInPictureState(new HolographicPreviewPipRenderState(
                null, new org.joml.Vector3f(), 1.0F, 0.0F, 0.0F, false, 70.0F, false,
                -1, NO_FLOATS, NO_FLOATS, NO_FLOATS, NO_FLOATS, NO_FLOATS,
                NO_FLOATS, NO_FLOATS, NO_FLOATS, NO_INTS,
                NO_FLOATS, NO_FLOATS, NO_FLOATS, NO_FLOATS, NO_FLOATS, NO_FLOATS,
                NO_FLOATS, NO_FLOATS, 0, 0, true, true, renderTerrain,
                origin.getX(), origin.getY(), origin.getZ(), 56.0F, 16.0F, 56.0F,
                renderTerrain ? TerrainPreviewManager.frame() : TerrainPreviewFrame.empty(), cameraFrame,
                x, y, x + w, y + h, Math.min(w, h), viewportBounds));
        graphics.outline(x, y, w, h, 0xFF45E7FF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
