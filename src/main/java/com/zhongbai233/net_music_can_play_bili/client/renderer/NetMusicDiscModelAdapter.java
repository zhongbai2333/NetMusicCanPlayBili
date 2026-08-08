package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.github.tartaricacid.netmusic.client.model.ModelMusicPlayer;
import com.github.tartaricacid.netmusic.client.renderer.MusicPlayerRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;

/**
 * 将 NetMusic 自己烘焙的唱片子模型叠加到本模组唱片机机身上。
 *
 * <p>模型与 128×128 贴图始终从已加载的必需依赖 NetMusic 获取，本模组不复制或重打包其资源。
 * 下面的变换与 NetMusic 1.5.1 的唱片机渲染坐标一致；更换 Blockbench 机身后只需微调三个锚点常量。
 */
final class NetMusicDiscModelAdapter {
    private static final float MODEL_SCALE = (float) DiscPlacementPolicy.MODEL_SCALE;
    /** Blockbench 中八根唱片条共享的目标枢轴高度：Y = 3.6199。 */
    private static final double ANCHOR_Y = 1.3329083333333333D;
    private final ModelMusicPlayer.Block model;

    NetMusicDiscModelAdapter(BlockEntityRendererProvider.Context context) {
        ModelPart bakedRoot = context.bakeLayer(ModelMusicPlayer.LAYER);
        this.model = new ModelMusicPlayer.Block(bakedRoot);
        // createBodyLayer() 的直接子节点 "root" 是机身；"disc" 是独立唱片子树。
        // 每个 BER 都有自己的 bakeLayer 实例，因此隐藏它不会影响 NetMusic 自己的唱片机。
        if (bakedRoot.hasChild("root")) {
            bakedRoot.getChild("root").visible = false;
        }
    }

    void submit(boolean hasDisc, boolean playing, Direction facing, long gameTime, float partialTick,
            int lightCoords, ModelFeatureRenderer.CrumblingOverlay breakProgress,
            PoseStack poseStack, SubmitNodeCollector collector) {
        if (!hasDisc) {
            return;
        }
        MusicPlayerRenderState state = new MusicPlayerRenderState();
        state.facing = facing;
        state.hasDisc = true;
        state.discRotation = playing ? DiscRotationPolicy.rotationAt(gameTime, partialTick) : 0.0F;

        int clockwiseQuarterTurns = switch (facing) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        DiscPlacementPolicy.Placement placement =
            DiscPlacementPolicy.forClockwiseQuarterTurns(clockwiseQuarterTurns);

        poseStack.pushPose();
        poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
        poseStack.translate(placement.anchorX(), ANCHOR_Y, placement.anchorZ());
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - facing.get2DDataValue() * 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
        collector.submitModel(model, state, poseStack, ModelMusicPlayer.TEXTURE,
                lightCoords, OverlayTexture.NO_OVERLAY, 0, breakProgress);
        poseStack.popPose();
    }

}