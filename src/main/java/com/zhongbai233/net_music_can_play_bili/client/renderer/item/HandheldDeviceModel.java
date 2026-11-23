package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Centers the north-facing glass in each baked item model under its interactive screen. */
enum HandheldDeviceModel {
    MP4(14.0F, 7.51F),
    PAD(8.0F, 7.46F);

    private final ItemStackRenderState model = new ItemStackRenderState();
    private final float glassHeight;
    private final float frontZ;

    HandheldDeviceModel(float glassHeight, float frontZ) {
        this.glassHeight = glassHeight;
        this.frontZ = frontZ;
    }

    void submit(AbstractClientPlayer player, ItemStack stack, PoseStack pose, SubmitNodeCollector collector,
            int light, float screenHeight, float screenFaceZ) {
        Minecraft.getInstance().getItemModelResolver().updateForLiving(model, stack, ItemDisplayContext.NONE, player);
        float scale = 16.0F * screenHeight / glassHeight;
        pose.pushPose();
        pose.translate(0.0F, 0.0F, screenFaceZ - 0.004F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.scale(scale, scale, scale);
        pose.translate(0.0F, 0.0F, (8.0F - frontZ) / 16.0F);
        model.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
    }
}
