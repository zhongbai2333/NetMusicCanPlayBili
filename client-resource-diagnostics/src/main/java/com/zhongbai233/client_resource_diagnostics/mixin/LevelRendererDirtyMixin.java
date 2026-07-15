package com.zhongbai233.client_resource_diagnostics.mixin;

import com.zhongbai233.client_resource_diagnostics.DiagnosticsController;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererDirtyMixin {
    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void clientdiag$dirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        DiagnosticsController.INSTANCE.recordDirtyRange(minX, minY, minZ, maxX, maxY, maxZ);
    }
}