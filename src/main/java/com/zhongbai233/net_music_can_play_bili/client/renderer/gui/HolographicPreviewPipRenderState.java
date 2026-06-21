package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

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
        float[] screenHeights, float[] screenAspects, float[] screenRolls,
        int x0, int y0, int x1, int y1, float scale,
        ScreenRectangle scissorArea, ScreenRectangle bounds) implements PictureInPictureRenderState {
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
                new float[] { screenHeight }, new float[] { screenAspect }, new float[] { screenRoll },
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
                gizmoTool, gizmoHandle, localSpace, selectedScreen, screenDistances, screenOffsetXs, screenOffsetYs,
                screenHeights, screenAspects, screenRolls, x0, y0, x1, y1, scale, scissorArea,
                PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }

    private static float valueAt(float[] values, int index, float fallback) {
        return values != null && index >= 0 && index < values.length ? values[index] : fallback;
    }

    @Override
    public Matrix3x2f pose() {
        return PictureInPictureRenderState.IDENTITY_POSE;
    }
}