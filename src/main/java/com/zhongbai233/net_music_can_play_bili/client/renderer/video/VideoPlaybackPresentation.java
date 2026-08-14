package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.slf4j.Logger;

import java.util.List;

/** Owns frame snapshots, projector submission, visibility tracking, and Iris YUV presentation. */
final class VideoPlaybackPresentation {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final VideoPipelineProperties.Offscreen OFFSCREEN = VideoPipelineProperties.offscreen();
    private static final long RESUME_RESTART_LAG_NANOS = OFFSCREEN.resumeRestartLagMillis() * 1_000_000L;
    private static final double PREWARM_DOT_THRESHOLD = OFFSCREEN.prewarmDotThreshold();
    private static final long STABILIZATION_MILLIS =
            VideoPipelineProperties.timing().decoderStabilizationMillis();

    private final VideoPlaybackInstance owner;

    VideoPlaybackPresentation(VideoPlaybackInstance owner) {
        this.owner = owner;
    }

    VideoBillboardPreview.ProjectorFrameSnapshot frameSnapshot(BlockPos projectorPos) {
        if (!owns(projectorPos)) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        if (owner.terminalFailure && VideoPlaceholderFrames.NETWORK_ERROR_ENABLED) {
            return placeholder(VideoPlaceholderFrames.Kind.NETWORK_ERROR);
        }
        return owner.hasFrame ? current() : VideoBillboardPreview.ProjectorFrameSnapshot.empty();
    }

    VideoBillboardPreview.ProjectorFrameSnapshot displayFrameSnapshot(BlockPos projectorPos) {
        if (!owns(projectorPos)) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        boolean placeholders = VideoPipelineProperties.loadingPlaceholderEnabled();
        if (owner.terminalFailure && VideoPlaceholderFrames.NETWORK_ERROR_ENABLED) {
            return placeholder(VideoPlaceholderFrames.Kind.NETWORK_ERROR);
        }
        boolean irisWarning = shouldShowIrisWarning();
        if (irisWarning && placeholders) {
            return placeholder(VideoPlaceholderFrames.Kind.IRIS_WARNING);
        }
        if (owner.hasFrame) {
            return irisWarning ? VideoBillboardPreview.ProjectorFrameSnapshot.empty() : current();
        }
        return placeholders ? placeholder(VideoPlaceholderFrames.Kind.LOADING)
                : VideoBillboardPreview.ProjectorFrameSnapshot.empty();
    }

    VideoBillboardPreview.ProjectorFrameSnapshot realFrameSnapshot(BlockPos projectorPos) {
        return owns(projectorPos) && owner.hasFrame
                ? current() : VideoBillboardPreview.ProjectorFrameSnapshot.empty();
    }

    VideoBillboardPreview.ProjectorFrameSnapshot failurePlaceholderSnapshot() {
        return placeholder(VideoPlaceholderFrames.Kind.NETWORK_ERROR);
    }

    VideoBillboardPreview.ProjectorFrameSnapshot turntableFrameSnapshot(BlockPos turntablePos) {
        if (turntablePos == null || !owner.anchor.isForTurntable(turntablePos)) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        if (owner.terminalFailure && VideoPlaceholderFrames.NETWORK_ERROR_ENABLED) {
            return placeholder(VideoPlaceholderFrames.Kind.NETWORK_ERROR);
        }
        return owner.hasFrame ? current() : VideoBillboardPreview.ProjectorFrameSnapshot.empty();
    }

    VideoBillboardPreview.ProjectorFrameSnapshot previewFrameSnapshot() {
        if (owner.terminalFailure && VideoPlaceholderFrames.NETWORK_ERROR_ENABLED) {
            return placeholder(VideoPlaceholderFrames.Kind.NETWORK_ERROR);
        }
        return owner.hasFrame ? current() : VideoBillboardPreview.ProjectorFrameSnapshot.empty();
    }

    private boolean owns(BlockPos projectorPos) {
        return projectorPos == null || owner.consumers.containsProjector(projectorPos);
    }

    private VideoBillboardPreview.ProjectorFrameSnapshot placeholder(VideoPlaceholderFrames.Kind kind) {
        return VideoPlaceholderFrames.snapshot(kind, owner.startNanoTime);
    }

    private VideoBillboardPreview.ProjectorFrameSnapshot current() {
        return owner.textures.snapshot(owner.targetWidth, owner.targetHeight);
    }

    void submit(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera) {
        boolean renderable = false;
        boolean prewarm = false;
        List<BlockPos> projectorPositions = owner.consumers.projectors();
        for (BlockPos pos : projectorPositions) {
            boolean berManaged = VideoBillboardPreview.isProjectorRenderedByBer(pos);
            boolean submittedByBer = VideoBillboardPreview.wasProjectorRecentlySubmittedByBer(owner.sessionId(), pos);
            if (VideoBerConsumerVisibilityPolicy.usesBerSubmission(berManaged, submittedByBer)) {
                renderable |= submittedByBer;
                prewarm |= submittedByBer;
                continue;
            }
            if (!(minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector)) {
                continue;
            }
            boolean projectorRenderable = VideoBillboardPreview.isProjectorScreenRenderable(minecraft, camera,
                    projector, VideoBillboardPreview.viewDotThreshold());
            boolean projectorPrewarm = projectorRenderable || VideoBillboardPreview.isProjectorScreenRenderable(
                    minecraft, camera, projector, PREWARM_DOT_THRESHOLD);
            renderable |= projectorRenderable;
            prewarm |= projectorPrewarm;
        }
        boolean holographicVisible = owner.hasHolographicTurntableConsumer();
        markVisibility(renderable || holographicVisible, prewarm || holographicVisible);
        owner.pumpUploadOnRenderThread();
        for (BlockPos pos : projectorPositions) {
            if (VideoBillboardPreview.isProjectorRenderedByBer(pos)
                    || !(minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector)) {
                continue;
            }
            submitProjector(event, minecraft, camera, projector);
        }
    }

    private void submitProjector(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector) {
        if (HolographicGlassesClient.shouldHideProjectorVideos()) {
            VideoBillboardPreview.submitProjectorPrivacyOverlay(event, minecraft, camera, projector);
        } else if (owner.networkFailure && VideoPlaceholderFrames.NETWORK_ERROR_ENABLED) {
            VideoBillboardPreview.submitProjectorEmissiveGeometry(event, minecraft, camera, projector,
                    placeholderTexture(VideoPlaceholderFrames.Kind.NETWORK_ERROR), VideoPlaceholderFrames.WIDTH,
                    VideoPlaceholderFrames.HEIGHT);
        } else if (owner.hasFrame && owner.textures.hasRgbaTexture()) {
            VideoBillboardPreview.submitProjectorGeometry(event, minecraft, camera, projector,
                    owner.textures.rgbaTextureId(), owner.targetWidth, owner.targetHeight);
        } else if (owner.hasFrame && owner.textures.hasYuvTexture()
                && VideoBillboardPreview.isCustomYuvShaderAvailable()
                && !VideoBillboardPreview.shouldDrawYuvImmediateWithIris()) {
            VideoBillboardPreview.submitProjectorYuvGeometry(event, minecraft, camera, projector,
                    owner.textures.yuvTextureSet());
        } else if (VideoPipelineProperties.loadingPlaceholderEnabled()) {
            submitLoadingPlaceholder(event, minecraft, camera, projector);
        }
    }

    private void submitLoadingPlaceholder(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector) {
        VideoPlaceholderFrames.Kind kind = shouldShowIrisWarning()
                ? VideoPlaceholderFrames.Kind.IRIS_WARNING : VideoPlaceholderFrames.Kind.LOADING;
        if (kind == VideoPlaceholderFrames.Kind.IRIS_WARNING && VideoPlaceholderFrames.IRIS_VIEW_DEPTH_OFFSET > 0.0D) {
            VideoBillboardPreview.submitProjectorViewDepthOffsetGeometry(event, minecraft, camera, projector,
                    placeholderTexture(kind), VideoPlaceholderFrames.WIDTH, VideoPlaceholderFrames.HEIGHT,
                    VideoPlaceholderFrames.IRIS_VIEW_DEPTH_OFFSET);
        } else {
            VideoBillboardPreview.submitProjectorEmissiveGeometry(event, minecraft, camera, projector,
                    placeholderTexture(kind), VideoPlaceholderFrames.WIDTH, VideoPlaceholderFrames.HEIGHT);
        }
    }

    private void markVisibility(boolean renderable, boolean prewarm) {
        long nowNs = System.nanoTime();
        owner.prewarmVisible = prewarm;
        if (renderable || prewarm) {
            long offscreenSince = owner.offscreenSinceNanoTime;
            owner.lastVisibleNanoTime = nowNs;
            owner.offscreenSinceNanoTime = 0L;
            if (offscreenSince > 0L) {
                maybeRestartForVisibleResume(nowNs - offscreenSince);
            }
            owner.loggedOffscreenPause = false;
        } else if (owner.offscreenSinceNanoTime == 0L) {
            owner.offscreenSinceNanoTime = nowNs;
        }
    }

    private void maybeRestartForVisibleResume(long offscreenDurationNs) {
        if (!owner.running || !restartAllowed() || RESUME_RESTART_LAG_NANOS <= 0L) {
            return;
        }
        long masterMillis = owner.anchor.timeline().mediaMillis();
        if (masterMillis < 0L) {
            return;
        }
        long bestVideoMillis = Math.max(owner.queuedMediaMillis(), owner.mediaMillis());
        long lagNs = bestVideoMillis >= 0L ? (masterMillis - bestVideoMillis) * 1_000_000L : offscreenDurationNs;
        if (lagNs < RESUME_RESTART_LAG_NANOS) {
            return;
        }
        long restartOffsetMillis = owner.totalMillis > 0L
                ? Math.min(owner.totalMillis, masterMillis) : masterMillis;
        LOGGER.debug("视频会话离屏恢复重定位: session={}, offscreen={}ms, master={}ms, video={}ms, offset={}ms",
                owner.sessionId(), offscreenDurationNs / 1_000_000L, masterMillis, bestVideoMillis,
                restartOffsetMillis);
        owner.restartDecoder(owner.targetWidth, owner.targetHeight, restartOffsetMillis, true);
    }

    boolean restartAllowed() {
        long generationStart = owner.decoderGenerationStartedNanoTime;
        long sinceStartMillis = generationStart > 0L
                ? Math.max(0L, (System.nanoTime() - generationStart) / 1_000_000L) : 0L;
        return VideoRestartSuppressionPolicy.allowsRestart(owner.liveSource, owner.restartInProgress,
                sinceStartMillis, STABILIZATION_MILLIS);
    }

    void renderYuvImmediate(RenderLevelStageEvent event, String route) {
        if ((owner.networkFailure && VideoPlaceholderFrames.NETWORK_ERROR_ENABLED) || !owner.hasFrame
                || !owner.textures.hasYuvTexture() || !VideoBillboardPreview.isCustomYuvShaderAvailable()
                || !VideoBillboardPreview.shouldDrawYuvImmediateWithIris()) {
            return;
        }
        owner.pumpUploadOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        boolean drew = false;
        boolean prewarm = false;
        for (BlockPos pos : owner.consumers.projectors()) {
            if (VideoBillboardPreview.drawCapturedProjectorYuvImmediate(event, owner.sessionId(), pos,
                    owner.textures.yuvTextureSet(), route)) {
                drew = true;
                prewarm = true;
                continue;
            }
            if (!(minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector)) {
                continue;
            }
            prewarm |= VideoBillboardPreview.isProjectorScreenRenderable(minecraft, camera, projector,
                    PREWARM_DOT_THRESHOLD);
            if (HolographicGlassesClient.shouldHideProjectorVideos()) {
                VideoBillboardPreview.drawProjectorPrivacyOverlayImmediate(event, minecraft, camera, projector, route);
                drew = true;
            } else {
                drew |= VideoBillboardPreview.drawProjectorYuvImmediate(event, minecraft, camera, projector,
                        owner.textures.yuvTextureSet(), route);
            }
        }
        markVisibility(drew, prewarm);
        if (drew && !owner.firstYuvImmediateLogged) {
            owner.firstYuvImmediateLogged = true;
            LOGGER.debug("Iris/YUV: session={} 的投影仪 YUV 使用实例纹理 immediate 绘制，route={}, texture={}x{}",
                    owner.sessionId(), route, owner.textures.yuvTextureSet().width(),
                    owner.textures.yuvTextureSet().height());
        }
    }

    private Identifier placeholderTexture(VideoPlaceholderFrames.Kind kind) {
        return VideoPlaceholderFrames.texture(kind, owner.startNanoTime);
    }

    private boolean shouldShowIrisWarning() {
        return owner.hasFrame && owner.textures.hasYuvTexture()
                && IrisShaderpackCompat.shouldApplyIrisYuvCompatibility();
    }

    boolean isWithinAudioRange(Minecraft minecraft) {
        return minecraft.player != null && owner.hasVideoConsumer()
                && owner.anchor.isWithinAudioRange(minecraft, owner.consumers.projectors(),
                        VideoBillboardPreview.AUDIO_SYNC_RANGE_SQR);
    }
}
