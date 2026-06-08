package com.zhongbai233.net_music_can_play_bili.mixin;

import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.zhongbai233.net_music_can_play_bili.client.renderer.IrisYuvShaderOverrideGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlDevice", priority = 500)
public abstract class GlDeviceYuvShaderSkipMixin {
    @Shadow
    private Map<RenderPipeline, GlRenderPipeline> pipelineCache;

    @Shadow
    private ShaderSource defaultShaderSource;

    @Shadow
    private GlRenderPipeline compilePipeline(RenderPipeline pipeline, ShaderSource shaderSource) {
        throw new AssertionError();
    }

    @Inject(method = "getOrCompilePipeline", at = @At("HEAD"), cancellable = true, require = 0)
    private void net_music_can_play_bili$bypassIrisProgramOverride(RenderPipeline pipeline,
            CallbackInfoReturnable<GlRenderPipeline> cir) {
        if (!IrisYuvShaderOverrideGuard.isYuvPipeline(pipeline)) {
            return;
        }
        IrisYuvShaderOverrideGuard.logBypassedShaderpackPipelineOnce();
        cir.setReturnValue(this.pipelineCache.computeIfAbsent(pipeline,
                key -> this.compilePipeline(key, this.defaultShaderSource)));
    }

    @Inject(method = "precompilePipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lcom/mojang/blaze3d/shaders/ShaderSource;)Lcom/mojang/blaze3d/opengl/GlRenderPipeline;", at = @At("HEAD"), cancellable = true, require = 0)
    private void net_music_can_play_bili$bypassIrisProgramOverrideDuringPrecompile(RenderPipeline pipeline,
            ShaderSource shaderSource, CallbackInfoReturnable<GlRenderPipeline> cir) {
        if (!IrisYuvShaderOverrideGuard.isYuvPipeline(pipeline)) {
            return;
        }
        ShaderSource effectiveShaderSource = shaderSource != null ? shaderSource : this.defaultShaderSource;
        IrisYuvShaderOverrideGuard.logBypassedShaderpackPipelineOnce();
        cir.setReturnValue(this.pipelineCache.computeIfAbsent(pipeline,
                key -> this.compilePipeline(key, effectiveShaderSource)));
    }

}