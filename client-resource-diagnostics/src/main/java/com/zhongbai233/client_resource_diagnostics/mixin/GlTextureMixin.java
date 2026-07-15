package com.zhongbai233.client_resource_diagnostics.mixin;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.zhongbai233.client_resource_diagnostics.DiagnosticsController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlTexture.class)
abstract class GlTextureMixin {
    @Unique
    private DiagnosticsController.ResourceToken clientdiag$token;
    @Unique
    private boolean clientdiag$released;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void clientdiag$created(int usage, String label, TextureFormat format, int width, int height,
            int depthOrLayers, int mipLevels, int id, CallbackInfo ci) {
        clientdiag$token = DiagnosticsController.INSTANCE.registerGpu(
                logicalBytes(format, width, height, depthOrLayers, mipLevels), true);
    }

    @Inject(method = "destroyImmediately", at = @At("HEAD"))
    private void clientdiag$destroyed(CallbackInfo ci) {
        if (!clientdiag$released) {
            clientdiag$released = true;
            DiagnosticsController.INSTANCE.releaseGpu(clientdiag$token);
        }
    }

    @Unique
    private static long logicalBytes(TextureFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        long total = 0L;
        for (int level = 0; level < Math.max(1, mipLevels); level++) {
            long w = Math.max(1, width >> level);
            long h = Math.max(1, height >> level);
            long layerBytes = saturatingMultiply(saturatingMultiply(w, h), Math.max(1, depthOrLayers));
            total = saturatingAdd(total, saturatingMultiply(layerBytes, format.pixelSize()));
        }
        return total;
    }

    @Unique
    private static long saturatingMultiply(long a, long b) {
        return a > 0L && b > Long.MAX_VALUE / a ? Long.MAX_VALUE : a * b;
    }

    @Unique
    private static long saturatingAdd(long a, long b) {
        return b > 0L && a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
}