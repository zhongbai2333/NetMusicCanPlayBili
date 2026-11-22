package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.systems.RenderSystem;

/** Capabilities of the active device, never inferred from the requested option. */
public enum VideoUploadBackend {
    OPENGL(true), VULKAN(false), OTHER(false);

    private final boolean supportsPbo;

    VideoUploadBackend(boolean supportsPbo) {
        this.supportsPbo = supportsPbo;
    }

    public boolean supportsPbo() {
        return supportsPbo;
    }

    public static VideoUploadBackend current() {
        return fromName(RenderSystem.getDevice().getDeviceInfo().backendName());
    }

    static VideoUploadBackend fromName(String name) {
        if ("OpenGL".equalsIgnoreCase(name)) {
            return OPENGL;
        }
        return "Vulkan".equalsIgnoreCase(name) ? VULKAN : OTHER;
    }
}
