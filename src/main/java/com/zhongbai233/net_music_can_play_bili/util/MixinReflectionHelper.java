package com.zhongbai233.net_music_can_play_bili.util;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;

/** Mixin 通用反射工具，避免多个 Mixin 中重复反射获取同一方法 */
public final class MixinReflectionHelper {

    /** Screen.addRenderableWidget(GuiEventListener) 的反射句柄 */
    public static final Method ADD_RENDERABLE_WIDGET;

    static {
        try {
            ADD_RENDERABLE_WIDGET = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
            ADD_RENDERABLE_WIDGET.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Failed to find Screen.addRenderableWidget", e);
        }
    }

    private MixinReflectionHelper() {
    }

    /** 通过反射向 Screen 添加可渲染控件 */
    public static void addWidget(Screen screen, GuiEventListener widget) {
        try {
            ADD_RENDERABLE_WIDGET.invoke(screen, widget);
        } catch (Exception e) {
            throw new RuntimeException("Failed to add widget via reflection", e);
        }
    }
}
