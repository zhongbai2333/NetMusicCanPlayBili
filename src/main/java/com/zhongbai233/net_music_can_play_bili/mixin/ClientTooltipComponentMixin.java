package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.client.tooltip.ClientMP4QueueTooltip;
import com.zhongbai233.net_music_can_play_bili.client.tooltip.MP4QueueTooltip;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientTooltipComponent.class)
public interface ClientTooltipComponentMixin {
    @Inject(method = "create(Lnet/minecraft/world/inventory/tooltip/TooltipComponent;)Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;", at = @At("HEAD"), cancellable = true)
    private static void net_music_can_play_bili$createMp4QueueTooltip(TooltipComponent component,
            CallbackInfoReturnable<ClientTooltipComponent> cir) {
        if (component instanceof MP4QueueTooltip tooltip) {
            cir.setReturnValue(new ClientMP4QueueTooltip(tooltip));
        }
    }
}
