package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * 保留视频自定义 pipeline 的透明混合状态，但让 26.1 feature renderer 将几何归入
 * solid/cutout 阶段。这样 opacity 仍由 GPU 混合处理，同时视频会在云和粒子之前写入深度。
 */
final class SolidClassifiedVideoRenderType extends RenderType {
    SolidClassifiedVideoRenderType(String name, RenderSetup setup) {
        super(name, setup);
    }

    @Override
    public boolean hasBlending() {
        return false;
    }
}