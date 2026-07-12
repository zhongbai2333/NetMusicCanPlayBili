package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.block.VideoProjectorBlock;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MinecartRevolution 成功装载投影仪后，清除玩家手中剩余物品的临时唱片机链接。 */
@Pseudo
@Mixin(targets = "ml.mypals.minecartrevolution.events.MinecartInteractionEventHandler", remap = false)
public abstract class MinecartRevolutionProjectorLinkCleanupMixin {
    @Inject(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;consume(ILnet/minecraft/world/entity/LivingEntity;)V", shift = At.Shift.AFTER), require = 1)
    private static void net_music_can_play_bili$clearConsumedProjectorLink(Player player, InteractionHand hand,
            AbstractMinecart interacted, Level level, CallbackInfo ci) {
        if (level.isClientSide()) {
            return;
        }
        ItemStack remainder = player.getItemInHand(hand);
        if (remainder.isEmpty() || !remainder.is(ModItems.VIDEO_PROJECTOR.get())) {
            return;
        }
        LinkHelper.clearLinkFromItem(remainder);
        VideoProjectorBlock.clearLinkedBlockEntityData(remainder);
    }
}