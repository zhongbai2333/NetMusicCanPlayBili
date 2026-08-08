package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerRoamingMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void net_music_can_play_bili$consumeRoamingEscape(long window, int action, KeyEvent event,
            CallbackInfo callback) {
        if (ControlConsoleRoamingSession.handleEscape(event.key(), action)
            || ControlConsoleRoamingSession.handlePlacementKey(event.key(), action)) {
            callback.cancel();
        }
    }
}