package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import com.zhongbai233.scene_editor.core.camera.CameraFrame;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewFrame;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import com.zhongbai233.net_music_can_play_bili.link.HolographicScreenSettings;
import org.joml.Matrix3x2f;
import org.joml.Vector3f;

public record HolographicPreviewPipRenderState(EntityRenderState playerState, Vector3f playerTranslation,
        float playerScale, float previewYaw, float previewPitch, boolean firstPerson, float fovDegrees,
        boolean playerGlowing, float screenDistance, float screenOffsetX, float screenOffsetY,
        float screenHeight, float screenAspect, float screenRoll, int gizmoTool, int gizmoHandle, boolean localSpace,
        int selectedScreen, float[] screenDistances, float[] screenOffsetXs, float[] screenOffsetYs,
        float[] screenHeights, float[] screenAspects, float[] screenYaws, float[] screenPitches, float[] screenRolls,
        float[] screenScaleXs, float[] screenScaleYs, float[] screenScaleZs,
        float[] screenPivotXs, float[] screenPivotYs, float[] screenPivotZs,
        float[] screenSkewXByYs, float[] screenSkewYByXs,
        int[] elementTypes,
        boolean controlConsoleModel, boolean renderWorldTerrain, int worldOriginX, int worldOriginY, int worldOriginZ,
        float worldRangeX, float worldRangeY, float worldRangeZ,
        TerrainPreviewFrame terrainFrame, CameraFrame cameraFrame,
        int x0, int y0, int x1, int y1, float scale,
        ScreenRectangle scissorArea, ScreenRectangle bounds) implements PictureInPictureRenderState {
    public HolographicPreviewPipRenderState {
        terrainFrame = terrainFrame != null ? terrainFrame : TerrainPreviewFrame.empty();
        playerTranslation = new Vector3f(playerTranslation);
        screenDistances = copy(screenDistances);
        screenOffsetXs = copy(screenOffsetXs);
        screenOffsetYs = copy(screenOffsetYs);
        screenHeights = copy(screenHeights);
        screenAspects = copy(screenAspects);
        screenYaws = copy(screenYaws);
        screenPitches = copy(screenPitches);
        screenRolls = copy(screenRolls);
        screenScaleXs = copy(screenScaleXs);
        screenScaleYs = copy(screenScaleYs);
        screenScaleZs = copy(screenScaleZs);
        screenPivotXs = copy(screenPivotXs);
        screenPivotYs = copy(screenPivotYs);
        screenPivotZs = copy(screenPivotZs);
        screenSkewXByYs = copy(screenSkewXByYs);
        screenSkewYByXs = copy(screenSkewYByXs);
        elementTypes = copy(elementTypes);
    }

    public HolographicPreviewPipRenderState(EntityRenderState playerState, Vector3f playerTranslation,
            float playerScale, float previewYaw, float previewPitch, boolean firstPerson, float fovDegrees,
            boolean playerGlowing, float screenDistance, float screenOffsetX, float screenOffsetY,
            float screenHeight, float screenAspect, float screenRoll, int gizmoTool, int gizmoHandle,
            boolean localSpace,
            int x0, int y0, int x1, int y1, float scale, ScreenRectangle scissorArea) {
        this(playerState, playerTranslation, playerScale, previewYaw, previewPitch, firstPerson, fovDegrees,
                playerGlowing,
                screenDistance, screenOffsetX, screenOffsetY,
                screenHeight, screenAspect, screenRoll, gizmoTool, gizmoHandle, localSpace, 0,
                new float[] { screenDistance }, new float[] { screenOffsetX }, new float[] { screenOffsetY },
                new float[] { screenHeight }, new float[] { screenAspect }, new float[] { 0.0F },
                new float[] { 0.0F }, new float[] { screenRoll },
                new float[] { 1.0F }, new float[] { 1.0F }, new float[] { 1.0F },
                new float[] { 0.0F }, new float[] { 0.0F }, new float[] { 0.0F },
                new float[] { 0.0F }, new float[] { 0.0F }, new int[] { 0 },
                false, false, 0, 0, 0, 0.0F, 0.0F, 0.0F, TerrainPreviewFrame.empty(), null,
                x0, y0, x1, y1, scale, scissorArea,
                PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }

    public HolographicPreviewPipRenderState(EntityRenderState playerState, Vector3f playerTranslation,
            float playerScale, float previewYaw, float previewPitch, boolean firstPerson, float fovDegrees,
            boolean playerGlowing, int selectedScreen, float[] screenDistances, float[] screenOffsetXs,
            float[] screenOffsetYs, float[] screenHeights, float[] screenAspects, float[] screenYaws,
            float[] screenPitches, float[] screenRolls, int[] elementTypes,
            float[] screenScaleXs, float[] screenScaleYs, float[] screenScaleZs,
            float[] screenPivotXs, float[] screenPivotYs, float[] screenPivotZs,
            float[] screenSkewXByYs, float[] screenSkewYByXs,
            int gizmoTool, int gizmoHandle, boolean localSpace,
            boolean controlConsoleModel, boolean renderWorldTerrain, int worldOriginX, int worldOriginY,
            int worldOriginZ, float worldRangeX, float worldRangeY, float worldRangeZ,
            TerrainPreviewFrame terrainFrame, CameraFrame cameraFrame,
            int x0, int y0, int x1, int y1, float scale,
            ScreenRectangle scissorArea) {
        this(playerState, playerTranslation, playerScale, previewYaw, previewPitch, firstPerson, fovDegrees,
                playerGlowing, valueAt(screenDistances, selectedScreen, HolographicScreenSettings.DEFAULT_DISTANCE),
                valueAt(screenOffsetXs, selectedScreen, HolographicScreenSettings.DEFAULT_OFFSET_X),
                valueAt(screenOffsetYs, selectedScreen, HolographicScreenSettings.DEFAULT_OFFSET_Y),
                valueAt(screenHeights, selectedScreen, HolographicScreenSettings.DEFAULT_HEIGHT),
                valueAt(screenAspects, selectedScreen, HolographicScreenSettings.DEFAULT_ASPECT),
                valueAt(screenRolls, selectedScreen, HolographicScreenSettings.DEFAULT_ROLL), gizmoTool,
                gizmoHandle, localSpace, selectedScreen, screenDistances, screenOffsetXs, screenOffsetYs,
                screenHeights, screenAspects, screenYaws, screenPitches, screenRolls,
                screenScaleXs, screenScaleYs, screenScaleZs, screenPivotXs, screenPivotYs, screenPivotZs,
                screenSkewXByYs, screenSkewYByXs, elementTypes, controlConsoleModel,
                renderWorldTerrain, worldOriginX, worldOriginY, worldOriginZ, worldRangeX, worldRangeY, worldRangeZ,
                terrainFrame, cameraFrame,
                x0, y0, x1, y1, scale, scissorArea,
                PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }

    public HolographicPreviewPipRenderState(EntityRenderState playerState, Vector3f playerTranslation,
            float playerScale, float previewYaw, float previewPitch, boolean firstPerson, float fovDegrees,
            boolean playerGlowing, int selectedScreen, float[] screenDistances, float[] screenOffsetXs,
            float[] screenOffsetYs, float[] screenHeights, float[] screenAspects, float[] screenRolls,
            int gizmoTool, int gizmoHandle, boolean localSpace, int x0, int y0, int x1, int y1, float scale,
            ScreenRectangle scissorArea) {
            this(playerState, playerTranslation, playerScale, previewYaw, previewPitch, firstPerson, fovDegrees,
                playerGlowing, valueAt(screenDistances, selectedScreen, HolographicScreenSettings.DEFAULT_DISTANCE),
                valueAt(screenOffsetXs, selectedScreen, HolographicScreenSettings.DEFAULT_OFFSET_X),
                valueAt(screenOffsetYs, selectedScreen, HolographicScreenSettings.DEFAULT_OFFSET_Y),
                valueAt(screenHeights, selectedScreen, HolographicScreenSettings.DEFAULT_HEIGHT),
                valueAt(screenAspects, selectedScreen, HolographicScreenSettings.DEFAULT_ASPECT),
                valueAt(screenRolls, selectedScreen, HolographicScreenSettings.DEFAULT_ROLL),
                gizmoTool, gizmoHandle, localSpace, selectedScreen, screenDistances, screenOffsetXs, screenOffsetYs, screenHeights,
                screenAspects, new float[screenRolls.length], new float[screenRolls.length], screenRolls,
                ones(screenRolls.length), ones(screenRolls.length), ones(screenRolls.length),
                new float[screenRolls.length], new float[screenRolls.length], new float[screenRolls.length],
                new float[screenRolls.length], new float[screenRolls.length], new int[screenRolls.length],
                false, false, 0, 0, 0, 0.0F, 0.0F, 0.0F, TerrainPreviewFrame.empty(), null,
                x0, y0, x1, y1, scale, scissorArea,
                PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
            }

            public HolographicPreviewPipRenderState(EntityRenderState playerState, Vector3f playerTranslation,
                float playerScale, float previewYaw, float previewPitch, boolean firstPerson, float fovDegrees,
                boolean playerGlowing, int selectedScreen, float[] screenDistances, float[] screenOffsetXs,
                float[] screenOffsetYs, float[] screenHeights, float[] screenAspects, float[] screenRolls,
                int gizmoTool, int gizmoHandle, boolean localSpace, CameraFrame cameraFrame,
                int x0, int y0, int x1, int y1, float scale, ScreenRectangle scissorArea) {
        this(playerState, playerTranslation, playerScale, previewYaw, previewPitch, firstPerson, fovDegrees,
                playerGlowing, valueAt(screenDistances, selectedScreen, HolographicScreenSettings.DEFAULT_DISTANCE),
                valueAt(screenOffsetXs, selectedScreen, HolographicScreenSettings.DEFAULT_OFFSET_X),
                valueAt(screenOffsetYs, selectedScreen, HolographicScreenSettings.DEFAULT_OFFSET_Y),
                valueAt(screenHeights, selectedScreen, HolographicScreenSettings.DEFAULT_HEIGHT),
                valueAt(screenAspects, selectedScreen, HolographicScreenSettings.DEFAULT_ASPECT),
                valueAt(screenRolls, selectedScreen, HolographicScreenSettings.DEFAULT_ROLL),
                gizmoTool, gizmoHandle, localSpace, selectedScreen, screenDistances, screenOffsetXs, screenOffsetYs,
                screenHeights, screenAspects, new float[screenRolls.length], new float[screenRolls.length], screenRolls,
                ones(screenRolls.length), ones(screenRolls.length), ones(screenRolls.length),
                new float[screenRolls.length], new float[screenRolls.length], new float[screenRolls.length],
                new float[screenRolls.length], new float[screenRolls.length], new int[screenRolls.length],
                false, false, 0, 0, 0, 0.0F, 0.0F, 0.0F, TerrainPreviewFrame.empty(), cameraFrame,
                x0, y0, x1, y1, scale, scissorArea,
                PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }

    private static float[] copy(float[] values) {
        return values != null ? values.clone() : null;
    }

    private static int[] copy(int[] values) {
        return values != null ? values.clone() : null;
    }

    private static float[] ones(int length) {
        float[] values = new float[Math.max(0, length)];
        java.util.Arrays.fill(values, 1.0F);
        return values;
    }

    private static float valueAt(float[] values, int index, float fallback) {
        return values != null && index >= 0 && index < values.length ? values[index] : fallback;
    }

    @Override
    public Matrix3x2f pose() {
        return PictureInPictureRenderState.IDENTITY_POSE;
    }
}
