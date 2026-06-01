package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.zhongbai233.net_music_can_play_bili.block.SpeakerBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * 音响方块渲染器 — 监听连接的唱片机状态，同步 ACTIVATED 方块状态
 */
public class SpeakerRenderer implements BlockEntityRenderer<SpeakerBlockEntity, SpeakerRenderer.State> {

    public SpeakerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(SpeakerBlockEntity speaker, State state, float partialTick,
            net.minecraft.world.phys.Vec3 cameraPos,
            net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(speaker, state, partialTick, cameraPos, crumblingOverlay);

        boolean active = false;
        BlockPos linked = speaker.getLinkedTurntablePos();
        if (linked != null) {
            var level = speaker.getLevel();
            if (level != null
                    && level.getBlockEntity(linked) instanceof ModernTurntableBlockEntity turntable
                    && turntable.isPlaying()) {
                active = true;
            }
        }

        // 同步 ACTIVATED 方块状态
        BlockState currentState = speaker.getLevel() != null
                ? speaker.getLevel().getBlockState(speaker.getBlockPos())
                : null;
        if (currentState != null && currentState.hasProperty(SpeakerBlock.ACTIVATED)) {
            boolean currentlyActivated = currentState.getValue(SpeakerBlock.ACTIVATED);
            if (active != currentlyActivated) {
                final boolean newValue = active;
                Minecraft.getInstance().execute(() -> {
                    var lvl = speaker.getLevel();
                    if (lvl != null) {
                        BlockPos pos = speaker.getBlockPos();
                        BlockState bs = lvl.getBlockState(pos);
                        if (bs.hasProperty(SpeakerBlock.ACTIVATED)) {
                            lvl.setBlock(pos, bs.setValue(SpeakerBlock.ACTIVATED, newValue), 3);
                        }
                    }
                });
            }
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState cameraState) {
        // 模型由 Minecraft 内置方块模型渲染器处理，此处无需额外绘制
    }

    public static class State extends BlockEntityRenderState {
    }
}
