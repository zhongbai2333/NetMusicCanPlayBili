package com.zhongbai233.net_music_can_play_bili.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4ItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 将第一人称 MP4 物品渲染替换为可交互的手持设备屏幕。
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void net_music_can_play_bili$renderMp4AsMap(AbstractClientPlayer player, float partialTick, float pitch,
            InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress, PoseStack poseStack,
            SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (stack.is(ModItems.MP4.get())) {
            MP4ItemScreenRenderer.renderMapLike(player, partialTick, pitch, hand, stack, swingProgress, equipProgress,
                    poseStack, collector, light, new MP4ItemScreenRenderer.ArmRenderer() {
                        @Override
                        public void renderMapHand(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                HumanoidArm arm) {
                            net_music_can_play_bili$renderMapHand(poseStack, collector, light, arm);
                        }

                        @Override
                        public void renderPlayerArm(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                float equipProgress, float swingProgress, HumanoidArm arm) {
                            net_music_can_play_bili$renderPlayerArm(poseStack, collector, light, equipProgress,
                                    swingProgress, arm);
                        }
                    });
            ci.cancel();
        }
    }

    @Invoker("renderMapHand")
    protected abstract void net_music_can_play_bili$renderMapHand(PoseStack poseStack, SubmitNodeCollector collector,
            int light, HumanoidArm arm);

    @Invoker("renderPlayerArm")
    protected abstract void net_music_can_play_bili$renderPlayerArm(PoseStack poseStack, SubmitNodeCollector collector,
            int light, float equipProgress, float swingProgress, HumanoidArm arm);

}
