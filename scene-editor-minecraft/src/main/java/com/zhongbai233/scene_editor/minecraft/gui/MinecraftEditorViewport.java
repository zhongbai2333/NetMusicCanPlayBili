package com.zhongbai233.scene_editor.minecraft.gui;

import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import net.minecraft.client.Minecraft;

import java.util.Objects;

/** Converts Minecraft's scaled window into the core's immutable GUI viewport. */
public final class MinecraftEditorViewport {
    private MinecraftEditorViewport() {
    }

    public static EditorViewport fullWindow(Minecraft minecraft) {
        Minecraft checked = Objects.requireNonNull(minecraft, "minecraft");
        return new EditorViewport(0, 0, Math.max(1, checked.getWindow().getGuiScaledWidth()),
                Math.max(1, checked.getWindow().getGuiScaledHeight()));
    }

    public static EditorViewport rectangle(int x, int y, int width, int height) {
        return new EditorViewport(x, y, Math.max(1, width), Math.max(1, height));
    }
}
