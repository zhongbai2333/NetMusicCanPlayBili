package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * 保留视频自定义 pipeline 的实际颜色状态，但让 26.1 feature renderer 将几何归入
 * solid/cutout 阶段。用于验证 Iris shaderpack 下的透明阶段分类是否导致画面伪影或不可见。
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