package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.zhongbai233.net_music_can_play_bili.block.VideoProjectorBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.mojang.math.Axis;
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
    private static final double PROJECTOR_RENDER_MARGIN = Double.parseDouble(
            System.getProperty("ncpb.video.projector.render_margin", "12.0"));
    private static final double PROJECTOR_RENDER_MIN_INFLATE = Double.parseDouble(
            System.getProperty("ncpb.video.projector.render_min_inflate", "16.0"));
    private static final double PROJECTOR_RENDER_MAX_ASPECT = Double.parseDouble(
            System.getProperty("ncpb.video.projector.render_max_aspect", "2.4"));

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
        state.projectorPos = projector.getBlockPos().immutable();
        state.linkedPos = null;
        state.projectionYaw = projector.getProjectionYaw();
        state.projectionPitch = projector.getProjectionPitch();
        state.projectionScale = projector.getProjectionScale();
        state.projectionHeight = projector.getProjectionHeight();
        state.projectionDistanceX = projector.getProjectionDistanceX();
        state.projectionDistanceZ = projector.getProjectionDistanceZ();
        state.frame = VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        state.hideVideoForPrivacy = HolographicGlassesClient.shouldHideProjectorVideos();
        state.visible = false;
        BlockPos linkedPos = projector.getLinkedTurntablePos();
        if (linkedPos == null || projector.getLevel() == null) {
            ClientLinkRegistry.unlink(projector.getBlockPos());
            VideoBillboardPreview.stopIfProjector(projector.getBlockPos());
            syncActivatedState(projector, false);
            return;
        }
        state.linkedPos = linkedPos.immutable();
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
                ModernTurntableVideoClient.syncFromTurntableForProjectorIfPossible(turntable, projector);
            }
            state.frame = VideoBillboardPreview.currentProjectorDisplayFrame(projector.getBlockPos());
        }
        if (!state.visible) {
            VideoBillboardPreview.stopIfProjector(projector.getBlockPos());
        }
        syncActivatedState(projector, state.visible);
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState cameraState) {
        if (!state.visible || state.linkedPos == null || state.projectorPos == null) {
            return;
        }
        VideoBillboardPreview.ProjectorFrameSnapshot frame = state.frame;
        if (frame == null || !frame.hasFrame() || frame.width() <= 0 || frame.height() <= 0) {
            return;
        }

        float scale = Math.abs(state.projectionScale);
        float aspect = frame.width() / (float) frame.height();
        float halfHeight = 1.35F * scale * 0.5F;
        float halfWidth = halfHeight * aspect;

        poseStack.pushPose();
        poseStack.translate(0.5D + state.projectionDistanceX, state.projectionHeight,
                0.5D + state.projectionDistanceZ);
        poseStack.mulPose(Axis.YP.rotationDegrees(state.projectionYaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(-state.projectionPitch));

        if (state.hideVideoForPrivacy) {
            VideoBillboardPreview.submitProjectorPrivacyOverlayOnPose(collector, poseStack, halfWidth, halfHeight);
        } else {
            VideoBillboardPreview.submitProjectorFrameOnPose(collector, poseStack, frame, halfWidth, halfHeight);
        }
        poseStack.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(VideoProjectorBlockEntity blockEntity) {
        double scale = Math.max(0.25D, Math.abs(blockEntity.getProjectionScale()));
        double halfHeight = 1.35D * scale * 0.5D;
        double halfWidth = halfHeight * Math.max(1.0D, PROJECTOR_RENDER_MAX_ASPECT);
        double screenRadius = Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);
        double offsetRadius = Math.sqrt(
                blockEntity.getProjectionDistanceX() * blockEntity.getProjectionDistanceX()
                        + blockEntity.getProjectionHeight() * blockEntity.getProjectionHeight()
                        + blockEntity.getProjectionDistanceZ() * blockEntity.getProjectionDistanceZ());
        double inflate = Math.max(PROJECTOR_RENDER_MIN_INFLATE, offsetRadius + screenRadius + PROJECTOR_RENDER_MARGIN);
        return new AABB(blockEntity.getBlockPos()).inflate(inflate, inflate, inflate);
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
        public BlockPos projectorPos;
        public BlockPos linkedPos;
        public float projectionYaw;
        public float projectionPitch;
        public float projectionScale;
        public float projectionHeight;
        public float projectionDistanceX;
        public float projectionDistanceZ;
        public boolean hideVideoForPrivacy;
        public VideoBillboardPreview.ProjectorFrameSnapshot frame = VideoBillboardPreview.ProjectorFrameSnapshot
                .empty();
    }
}