package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 灵魂漫游使用自己的命中反馈准星，避免与原版准星叠加。 */
@Mixin(Gui.class)
public abstract class GuiRoamingCrosshairMixin {
    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void net_music_can_play_bili$hideVanillaCrosshair(GuiGraphicsExtractor graphics,
            DeltaTracker deltaTracker, CallbackInfo callback) {
        if (ControlConsoleRoamingSession.isActive()) {
            callback.cancel();
        }
    }
}