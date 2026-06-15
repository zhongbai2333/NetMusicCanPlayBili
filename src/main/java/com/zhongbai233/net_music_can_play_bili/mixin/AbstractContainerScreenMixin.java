package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import com.zhongbai233.net_music_can_play_bili.network.MP4QueueSelectionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin<T extends AbstractContainerMenu> {
    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void net_music_can_play_bili$selectMp4QueueItem(double mouseX, double mouseY, double scrollX,
            double scrollY, CallbackInfoReturnable<Boolean> cir) {
        if (this.hoveredSlot == null || scrollY == 0.0D) {
            return;
        }
        ItemStack stack = this.hoveredSlot.getItem();
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        int queueSize = MP4Item.queueSize(stack);
        if (queueSize <= 0) {
            return;
        }
        MP4Item.State state = MP4Client.cachedStateFor(stack);
        int selected = Math.max(0, Math.min(queueSize - 1, state.selectedQueueIndex() + (scrollY < 0.0D ? 1 : -1)));
        if (Minecraft.getInstance().getConnection() != null) {
            Minecraft.getInstance().getConnection().send(new MP4QueueSelectionPacket(this.hoveredSlot.index, selected));
        }
        cir.setReturnValue(true);
    }
}
