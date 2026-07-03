package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.platform.NativeImage;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** Pad 离屏 GUI 纹理入口。 */
final class PadGuiTexture implements AutoCloseable {
    static final int WIDTH = 448;
    static final int HEIGHT = 256;

    private static final Identifier WHITE_TEXTURE_ID = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "dynamic/pad_gui_white");
    private static DynamicTexture sharedWhiteTexture;

    private final PadOffscreenGuiRenderer offscreenRenderer;

    PadGuiTexture(String textureKey) {
        this.offscreenRenderer = new PadOffscreenGuiRenderer(textureKey);
    }

    Identifier textureId(UUID deviceId) {
        return offscreenRenderer.textureId(deviceId);
    }

    void renderFrameStart(UUID deviceId) {
        offscreenRenderer.renderFrameStart(deviceId, 1.0F);
    }

    void renderFrameStart(UUID deviceId, float partialTick) {
        offscreenRenderer.renderFrameStart(deviceId, partialTick);
    }

    void tickMapLayer(UUID deviceId) {
        offscreenRenderer.tickMapLayer(deviceId);
    }

    Identifier whiteTextureId() {
        ensureWhiteTexture();
        return WHITE_TEXTURE_ID;
    }

    void warmup() {
        ensureWhiteTexture();
    }

    private void ensureWhiteTexture() {
        if (sharedWhiteTexture != null) {
            return;
        }
        sharedWhiteTexture = new DynamicTexture("pad_gui_white", 1, 1, false);
        NativeImage image = sharedWhiteTexture.getPixels();
        if (image != null && !image.isClosed()) {
            image.setPixel(0, 0, 0xFFFFFFFF);
            sharedWhiteTexture.upload();
        }
        Minecraft.getInstance().getTextureManager().register(WHITE_TEXTURE_ID, sharedWhiteTexture);
    }

    @Override
    public void close() {
        offscreenRenderer.close();
    }
}