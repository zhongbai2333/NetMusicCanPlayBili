package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.renderer.texture.AbstractTexture;

/** 将 RenderTarget 颜色附件暴露为可采样纹理。 */
final class RenderTargetTexture extends AbstractTexture {
    private final RenderTarget target;

    RenderTargetTexture(RenderTarget target) {
        this.target = target;
        refreshView();
    }

    void refreshView() {
        this.texture = target.getColorTexture();
        this.textureView = target.getColorTextureView();
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    @Override
    public void close() {
        this.texture = null;
        this.textureView = null;
    }
}