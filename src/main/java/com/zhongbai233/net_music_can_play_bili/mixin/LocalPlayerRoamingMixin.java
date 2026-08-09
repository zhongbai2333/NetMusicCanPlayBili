package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerRoamingMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void net_music_can_play_bili$turnRoamingCamera(double yaw, double pitch, CallbackInfo callback) {
        if (ControlConsoleRoamingSession.turnCamera(yaw, pitch)) {
            callback.cancel();
        }
    }
}