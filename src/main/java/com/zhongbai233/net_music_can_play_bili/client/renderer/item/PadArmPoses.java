package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

public final class PadArmPoses {
    public static final EnumProxy<HumanoidModel.ArmPose> READING = new EnumProxy<>(HumanoidModel.ArmPose.class,
            true, true, (IArmPoseTransformer) PadArmPoses::read);
    public static final EnumProxy<HumanoidModel.ArmPose> CARRYING = new EnumProxy<>(HumanoidModel.ArmPose.class,
            false, false, (IArmPoseTransformer) PadArmPoses::carry);

    private PadArmPoses() {
    }

    public static void register(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                if (entity.isUsingItem() && entity.getUsedItemHand() != hand) {
                    return null;
                }
                InteractionHand other = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                return entity.getItemInHand(other).isEmpty() ? READING.getValue() : CARRYING.getValue();
            }
        }, ModItems.PAD.get());
    }

    private static void read(HumanoidModel<?> model, HumanoidRenderState state, HumanoidArm holdingArm) {
        float pitch = -1.10F + Mth.clamp(model.head.xRot, -0.7F, 0.7F) * 0.14F;
        position(model.rightArm, HumanoidArm.RIGHT, pitch, -0.13F);
        position(model.leftArm, HumanoidArm.LEFT, pitch, -0.13F);
    }

    private static void carry(HumanoidModel<?> model, HumanoidRenderState state, HumanoidArm holdingArm) {
        position(model.getArm(holdingArm), holdingArm, -0.95F, 0.33F);
    }

    private static void position(ModelPart arm, HumanoidArm side, float pitch, float yaw) {
        float mirror = side == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        arm.xRot = pitch;
        arm.yRot = mirror * yaw;
        arm.zRot = mirror * -0.03F;
    }
}
