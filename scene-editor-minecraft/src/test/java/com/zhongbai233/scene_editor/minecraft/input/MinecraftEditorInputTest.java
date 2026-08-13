package com.zhongbai233.scene_editor.minecraft.input;

import com.zhongbai233.scene_editor.core.camera.StandardCameraView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftEditorInputTest {
    private static final int GLFW_KEY_0 = 48;
    private static final int GLFW_KEY_1 = 49;
    private static final int GLFW_KEY_W = 87;

    @Test
    void mapsStandardViewsAndSeparatesFirstPersonFlyControls() {
        assertEquals(StandardCameraView.FRONT,
                MinecraftEditorInput.standardView(GLFW_KEY_1).orElseThrow());
        assertEquals(MinecraftEditorInput.FlyControl.FORWARD,
                MinecraftEditorInput.flyControl(GLFW_KEY_W, true).orElseThrow());
        assertEquals(MinecraftEditorInput.FlyControl.UP,
                MinecraftEditorInput.flyControl(GLFW_KEY_W, false).orElseThrow());
        assertTrue(MinecraftEditorInput.standardView(GLFW_KEY_0).isEmpty());
    }
}
