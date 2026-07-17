package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.HumanoidArm;

/** Shared first-person arm rendering bridge used by handheld item screens. */
public interface HandheldArmRenderer {
    void renderMapHand(PoseStack poseStack, SubmitNodeCollector collector, int light, HumanoidArm arm);

    void renderPlayerArm(PoseStack poseStack, SubmitNodeCollector collector, int light, float equipProgress,
            float swingProgress, HumanoidArm arm);
}