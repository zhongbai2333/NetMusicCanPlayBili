package com.zhongbai233.client_resource_diagnostics.mixin;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.zhongbai233.client_resource_diagnostics.DiagnosticsController;
import java.nio.ByteBuffer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlBuffer.class)
abstract class GlBufferMixin {
    @Unique
    private DiagnosticsController.ResourceToken clientdiag$token;
    @Unique
    private boolean clientdiag$released;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void clientdiag$created(@Nullable Supplier<String> label, DirectStateAccess dsa, int usage,
            long size, int handle, @Nullable ByteBuffer persistentBuffer, CallbackInfo ci) {
        clientdiag$token = DiagnosticsController.INSTANCE.registerGpu(size, false);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void clientdiag$closed(CallbackInfo ci) {
        if (!clientdiag$released) {
            clientdiag$released = true;
            DiagnosticsController.INSTANCE.releaseGpu(clientdiag$token);
        }
    }
}