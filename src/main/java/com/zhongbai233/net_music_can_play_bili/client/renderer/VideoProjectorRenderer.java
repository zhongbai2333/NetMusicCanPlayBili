package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.zhongbai233.net_music_can_play_bili.block.VideoProjectorBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
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
import org.joml.Matrix4f;

import java.util.Optional;

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
    private static final ProjectorRenderProperties.VideoBounds RENDER_BOUNDS =
            ProjectorRenderProperties.videoBounds();

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
        state.playbackSessionId = Optional.empty();
        state.hideVideoForPrivacy = com.zhongbai233.net_music_can_play_bili.client.renderer.video
            .VideoSurfacePrivacyPolicy.hideVideo(HolographicGlassesClient.shouldHideProjectorVideos(),
                com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoSurfacePrivacyPolicy
                    .SurfaceKind.PUBLIC_PROJECTOR);
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
        var linkedBlockEntity = level.getBlockEntity(linkedPos);
        // 直播机与唱片机都是合法的视频源；直播会话由 LiveStreamerVideoClient 驱动，
        // 渲染器只负责取帧和可见性。
        if (linkedBlockEntity instanceof com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity live) {
            ClientLinkRegistry.link(projector.getBlockPos(), linkedPos);
            state.visible = live.isPlaying();
            if (state.visible) {
                // 与唱片机一致：登记为 BER 渲染，避免 VideoPlaybackInstance 的
                // 备用几何路径同帧再画一张屏（姿态计算不同会呈交叉双片）。
                VideoBillboardPreview.attachProjectorToTurntable(linkedPos, projector.getBlockPos());
                String liveSessionId = com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker
                        .currentSessionId(linkedPos);
                state.playbackSessionId = PlaybackSessionId.parse(liveSessionId);
                state.frame = VideoBillboardPreview.currentProjectorDisplayFrame(projector.getBlockPos());
            } else {
                VideoBillboardPreview.stopIfProjector(projector.getBlockPos());
            }
            syncActivatedState(projector, state.visible);
            return;
        }
        if (!(linkedBlockEntity instanceof ModernTurntableBlockEntity turntable)) {
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
            state.playbackSessionId = sync.playbackSessionId();
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
        // submit 只会在 BER 通过引擎视锥/距离调度后执行；将这个逐帧事实与
        // extractRenderState 中登记的持久 BER 管理状态分开，供离屏暂停使用。
        if (!state.hideVideoForPrivacy
                && VideoBillboardPreview.isProjectorScreenPotentiallyVisible(state.projectorPos)) {
            state.playbackSessionId.ifPresent(sessionId ->
                    VideoBillboardPreview.markProjectorSubmittedByBer(sessionId, state.projectorPos));
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
        Matrix4f screenPose = new Matrix4f(poseStack.last().pose());

        if (state.playbackSessionId.isPresent() && !state.hideVideoForPrivacy) {
            VideoBillboardPreview.captureProjectorImmediatePose(state.playbackSessionId.orElseThrow(),
                    state.projectorPos, screenPose, halfHeight);
        }

        if (state.hideVideoForPrivacy) {
            VideoBillboardPreview.submitProjectorPrivacyOverlayOnPose(collector, poseStack, halfWidth, halfHeight);
        } else {
            VideoBillboardPreview.submitProjectorFrameOnPose(collector, poseStack, frame, halfWidth, halfHeight,
                    VideoBillboardPreview.cameraRelativeBackOffset(screenPose, frame.rgbaDepthOffset()));
        }
        poseStack.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(VideoProjectorBlockEntity blockEntity) {
        return ProjectorScreenBounds.aroundBlock(blockEntity.getBlockPos(),
                blockEntity.getProjectionDistanceX(), blockEntity.getProjectionHeight(),
                blockEntity.getProjectionDistanceZ(), blockEntity.getProjectionYaw(),
                blockEntity.getProjectionPitch(), blockEntity.getProjectionScale(),
                RENDER_BOUNDS.maxAspect(), RENDER_BOUNDS.margin());
    }

    @Override
    public boolean shouldRender(VideoProjectorBlockEntity blockEntity, Vec3 cameraPos) {
        double viewDistance = getViewDistance();
        return ProjectorScreenBounds.distanceToSqr(getRenderBoundingBox(blockEntity), cameraPos) < viewDistance
                * viewDistance;
    }

    private static void syncActivatedState(VideoProjectorBlockEntity projector, boolean visible) {
        var level = projector.getLevel();
        // MinecartRevolution 的模拟 level 会在 setBlock 时 refreshBlockEntity()。
        // 若在这里同步 ACTIVATED，会每帧移除并重建矿车内的投影仪 BE，继而停止、重建视频会话。
        if (level == null || level != Minecraft.getInstance().level)
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
        private Optional<PlaybackSessionId> playbackSessionId = Optional.empty();

        public Optional<PlaybackSessionId> playbackSessionId() {
            return playbackSessionId;
        }

        public String sessionId() {
            return playbackSessionId.map(session -> session.value()).orElse("");
        }
        public VideoBillboardPreview.ProjectorFrameSnapshot frame = VideoBillboardPreview.ProjectorFrameSnapshot
                .empty();
    }
}
