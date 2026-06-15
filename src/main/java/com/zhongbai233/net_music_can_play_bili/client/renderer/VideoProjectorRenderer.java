package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.zhongbai233.net_music_can_play_bili.block.VideoProjectorBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * 视频投影仪渲染器。
 *
 * <p>
 * 实际视频画面由 {@link VideoBillboardPreview}
 * 在全局几何提交事件中渲染；此渲染器负责维护客户端链接表和方块激活状态。
 * </p>
 */
public class VideoProjectorRenderer
        implements BlockEntityRenderer<VideoProjectorBlockEntity, VideoProjectorRenderer.State> {
    public VideoProjectorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(VideoProjectorBlockEntity projector, State state, float partialTick,
            Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(projector, state, partialTick, cameraPos, crumblingOverlay);
        state.visible = false;
        BlockPos linkedPos = projector.getLinkedTurntablePos();
        if (linkedPos == null || projector.getLevel() == null) {
            ClientLinkRegistry.unlink(projector.getBlockPos());
            VideoBillboardPreview.stopIfProjector(projector.getBlockPos());
            syncActivatedState(projector, false);
            return;
        }
        var level = projector.getLevel();
        if (!(level.getBlockEntity(linkedPos) instanceof ModernTurntableBlockEntity turntable)) {
            ClientLinkRegistry.unlink(projector.getBlockPos());
            VideoBillboardPreview.stopIfProjector(projector.getBlockPos());
            projector.unlink();
            syncActivatedState(projector, false);
            return;
        }
        ClientLinkRegistry.link(projector.getBlockPos(), linkedPos);
        state.visible = turntable.isPlaying();
        if (state.visible) {
            VideoBillboardPreview.attachProjectorToTurntable(linkedPos, projector.getBlockPos());
            var sync = turntable.getPlaybackSyncMetadata(level.getGameTime());
            if (!sync.hasSession() || !VideoBillboardPreview.hasSessionForTurntable(linkedPos, sync.sessionId())) {
                ModernTurntableVideoClient.syncFromTurntableIfPossible(turntable);
            }
        }
        if (!state.visible) {
            VideoBillboardPreview.stopIfProjector(projector.getBlockPos());
        }
        syncActivatedState(projector, state.visible);
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState cameraState) {
        // 视频画面由 VideoBillboardPreview 统一提交，避免每个 BE 重复持有动态纹理。
    }

    @Override
    public AABB getRenderBoundingBox(VideoProjectorBlockEntity blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(3.0D, 3.0D, 3.0D);
    }

    private static void syncActivatedState(VideoProjectorBlockEntity projector, boolean visible) {
        var level = projector.getLevel();
        if (level == null)
            return;
        BlockPos pos = projector.getBlockPos();
        BlockState currentState = level.getBlockState(pos);
        if (!currentState.hasProperty(VideoProjectorBlock.ACTIVATED))
            return;
        boolean currentlyActivated = currentState.getValue(VideoProjectorBlock.ACTIVATED);
        if (visible != currentlyActivated) {
            Minecraft.getInstance().execute(() -> {
                var lvl = projector.getLevel();
                if (lvl != null) {
                    BlockState bs = lvl.getBlockState(pos);
                    if (bs.hasProperty(VideoProjectorBlock.ACTIVATED)) {
                        lvl.setBlock(pos, bs.setValue(VideoProjectorBlock.ACTIVATED, visible), 3);
                    }
                }
            });
        }
    }

    public static class State extends BlockEntityRenderState {
        public boolean visible;
    }
}