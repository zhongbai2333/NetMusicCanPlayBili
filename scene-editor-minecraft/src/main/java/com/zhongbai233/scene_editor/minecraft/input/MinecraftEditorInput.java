package com.zhongbai233.scene_editor.minecraft.input;

import com.zhongbai233.scene_editor.core.camera.StandardCameraView;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/** Minecraft/GLFW key mapping kept outside the host-neutral editor core. */
public final class MinecraftEditorInput {
    private MinecraftEditorInput() {
    }

    public static Optional<StandardCameraView> standardView(int key) {
        return Optional.ofNullable(switch (key) {
            case GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_KP_1 -> StandardCameraView.FRONT;
            case GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_KP_2 -> StandardCameraView.BACK;
            case GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_KP_3 -> StandardCameraView.LEFT;
            case GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_KP_4 -> StandardCameraView.RIGHT;
            case GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_KP_5 -> StandardCameraView.TOP;
            case GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_KP_6 -> StandardCameraView.BOTTOM;
            default -> null;
        });
    }

    public static Optional<FlyControl> flyControl(int key, boolean forwardOnW) {
        return Optional.ofNullable(switch (key) {
            case GLFW.GLFW_KEY_W -> forwardOnW ? FlyControl.FORWARD : FlyControl.UP;
            case GLFW.GLFW_KEY_S -> forwardOnW ? FlyControl.BACKWARD : FlyControl.DOWN;
            case GLFW.GLFW_KEY_A -> FlyControl.LEFT;
            case GLFW.GLFW_KEY_D -> FlyControl.RIGHT;
            case GLFW.GLFW_KEY_C -> forwardOnW ? FlyControl.DOWN : null;
            case GLFW.GLFW_KEY_SPACE -> forwardOnW ? FlyControl.UP : null;
            case GLFW.GLFW_KEY_Q -> forwardOnW ? null : FlyControl.FORWARD;
            case GLFW.GLFW_KEY_E -> forwardOnW ? null : FlyControl.BACKWARD;
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> FlyControl.FAST;
            default -> null;
        });
    }

    public enum FlyControl {
        FORWARD,
        BACKWARD,
        LEFT,
        RIGHT,
        DOWN,
        UP,
        FAST
    }
}
