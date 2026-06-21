package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** MP4 GUI 离屏渲染期间使用的线程局部 RenderTarget 覆盖。 */
public final class OffscreenGuiRenderTargetContext {
    private static final ThreadLocal<RenderTarget> ACTIVE_TARGET = new ThreadLocal<>();

    private OffscreenGuiRenderTargetContext() {
    }

    public static RenderTarget activeTarget() {
        return ACTIVE_TARGET.get();
    }

    public static void withTarget(RenderTarget target, Runnable action) {
        RenderTarget previous = ACTIVE_TARGET.get();
        ACTIVE_TARGET.set(target);
        try {
            action.run();
        } finally {
            if (previous == null) {
                ACTIVE_TARGET.remove();
            } else {
                ACTIVE_TARGET.set(previous);
            }
        }
    }
}