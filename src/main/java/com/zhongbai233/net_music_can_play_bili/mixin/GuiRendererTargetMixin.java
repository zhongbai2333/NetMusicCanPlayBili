package com.zhongbai233.net_music_can_play_bili.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.OffscreenGuiRenderTargetContext;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererTargetMixin {
    @ModifyVariable(method = "executeDrawRange", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private RenderTarget net_music_can_play_bili$useOffscreenTarget(RenderTarget original) {
        RenderTarget target = OffscreenGuiRenderTargetContext.activeTarget();
        return target != null ? target : original;
    }
}