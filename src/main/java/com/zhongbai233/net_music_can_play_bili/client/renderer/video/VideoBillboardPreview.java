package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleVideoStatePolicy;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector3fc;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * B站视频投影的客户端渲染管线
 *
 * <p>
 * 该类负责管理视频解码会话，将解码后的 RGBA/YUV 帧上传为动态纹理，
 * 并在 {@link SubmitCustomGeometryEvent} 中把纹理提交为世界空间 billboard。正式播放时，
 * billboard 会锚定到视频投影仪的配置位置，并根据投影仪生命周期、播放会话、视距与视角进行裁剪
 * </p>
 */
@EventBusSubscriber(modid = NetMusicCanPlayBili.MODID, value = Dist.CLIENT)
public final class VideoBillboardPreview extends VideoBillboardSessionSupport {
    /**
     * 根据相机相对空间中的屏幕姿态，选择提示底片远离玩家的一侧。
     * BER 的最终矩阵以相机为原点，因此屏幕局部 +Z 法线与屏幕中心的点积
     * 可以直接判断哪一面朝向玩家。
     */
    public static float cameraRelativeBackOffset(Matrix4f screenPose, float configuredOffset) {
        float distance = Math.abs(configuredOffset);
        if (screenPose == null || distance <= 0.0F) {
            return 0.0F;
        }
        Vector4f center = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F).mul(screenPose);
        Vector4f positiveZ = new Vector4f(0.0F, 0.0F, 1.0F, 1.0F).mul(screenPose);
        float normalX = positiveZ.x - center.x;
        float normalY = positiveZ.y - center.y;
        float normalZ = positiveZ.z - center.z;
        float towardCameraDot = normalX * -center.x + normalY * -center.y + normalZ * -center.z;
        if (Math.abs(towardCameraDot) < 1.0e-6F) {
            return configuredOffset;
        }
        return towardCameraDot > 0.0F ? -distance : distance;
    }

    public static ProjectorFrameSnapshot currentProjectorFrame(BlockPos projectorPos) {
        if (projectorPos != null) {
            for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
                ProjectorFrameSnapshot snapshot = instance.frameSnapshot(projectorPos);
                if (snapshot.hasFrame()) {
                    return snapshot;
                }
            }
        }
        if (activeNetworkFailure && NETWORK_ERROR_PLACEHOLDER_ENABLED && width > 0 && height > 0
                && (projectorPos == null || LEGACY_PREVIEW.projectors().isEmpty()
                        || LEGACY_PREVIEW.projectors().contains(projectorPos))) {
            return networkErrorSnapshot();
        }
        if (!hasFrame || width <= 0 || height <= 0) {
            return ProjectorFrameSnapshot.empty();
        }
        if (projectorPos != null && !LEGACY_PREVIEW.projectors().isEmpty()
                && !LEGACY_PREVIEW.projectors().contains(projectorPos)) {
            return ProjectorFrameSnapshot.empty();
        }
        VideoYuvTextureSet yuvTextures = LEGACY_TEXTURES.yuv();
        if (shouldRenderYuvFrame() && yuvTextures != null) {
            return new ProjectorFrameSnapshot(true, true, TEXTURE_ID, yuvTextures.yId(), yuvTextures.uId(),
                    yuvTextures.vId(), yuvTextures.format(), width, height, false, false, 0.0F);
        }
        return new ProjectorFrameSnapshot(true, false, TEXTURE_ID, null, null, null,
                Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, width, height, false, false, 0.0F);
    }

    public static ProjectorFrameSnapshot currentProjectorDisplayFrame(BlockPos projectorPos) {
        if (projectorPos != null) {
            for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
                ProjectorFrameSnapshot snapshot = instance.displayFrameSnapshot(projectorPos);
                if (snapshot.hasFrame()) {
                    return snapshot;
                }
            }
            PendingVideoSessionRegistry.Snapshot<BlockPos> pending = PENDING_SESSIONS.findByProjector(
                    PendingVideoSessionRegistry.State.LOADING, projectorPos);
            if (pending != null) {
                return VideoPlaybackInstance.loadingPlaceholderSnapshot(pending.startedNanoTime());
            }
        }
        return currentProjectorFrame(projectorPos);
    }

    public static ProjectorFrameSnapshot currentTurntableFrame(BlockPos turntablePos) {
        if (turntablePos != null) {
            for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
                ProjectorFrameSnapshot snapshot = instance.turntableFrameSnapshot(turntablePos);
                if (snapshot.hasFrame()) {
                    return snapshot;
                }
            }
        }
        return ProjectorFrameSnapshot.empty();
    }

    private VideoBillboardPreview() {
    }

    private static ProjectorFrameSnapshot networkErrorSnapshot() {
        return new ProjectorFrameSnapshot(true, false, NETWORK_ERROR_PLACEHOLDER_TEXTURE, null, null, null,
                Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, LOADING_PLACEHOLDER_WIDTH,
                LOADING_PLACEHOLDER_HEIGHT, true, false, 0.0F);
    }

    public static void start(String videoUrl, int targetWidth, int targetHeight, int fps) {
        start(videoUrl, targetWidth, targetHeight, fps, null);
    }

    public static void start(String videoUrl, int targetWidth, int targetHeight, int fps, String decoderOverride) {
        start(videoUrl, targetWidth, targetHeight, fps, 7, false, decoderOverride);
    }

    public static void start(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride) {
        startInternal(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, "", 0L, 0L,
                null, true);
    }

    public static void startBenchPreview(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride) {
        startInternal(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, "", 0L, 0L,
                null, false);
    }

    public static void startPreviewAt(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, boolean preferNative, String decoderOverride) {
        startInternal(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride,
                sessionId == null ? "" : sessionId, Math.max(0L, startOffsetMillis), Math.max(0L, totalMillis),
                null, true, false);
    }

    public static void startRgbaPreviewAt(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, boolean preferNative, String decoderOverride) {
        startRgbaPreviewAt(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis,
                totalMillis, preferNative, decoderOverride, null);
    }

    public static void startRgbaPreviewAt(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, boolean preferNative, String decoderOverride,
            UUID sourceId) {
        if (!com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection.allowMediaStart()) {
            return;
        }
        String normalized = sessionId != null ? sessionId : "";
        if (normalized.isBlank()) {
            startInternal(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride,
                    normalized, Math.max(0L, startOffsetMillis), Math.max(0L, totalMillis), null, true, true);
            return;
        }
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(normalized).orElse(null);
        if (parsedSessionId == null) {
            return;
        }
        normalized = parsedSessionId.value();
        long offset = Math.max(0L, startOffsetMillis);
        VideoPlaybackInstance existing = SESSION_INSTANCES.get(normalized);
        if (existing != null) {
            existing.setGuiConsumer(true);
            if (existing.isRunningAtOffset(offset, 250L)
                    || existing.requestSyncedReseek(offset)) {
                return;
            }
        }
        VideoPlaybackInstance instance = new VideoPlaybackInstance(videoUrl, targetWidth, targetHeight, fps, codecId,
                normalized, offset, Math.max(0L, totalMillis), List.of(),
                sourceId != null
                        ? new PreviewVideoPlaybackAnchor(sourceId, normalized, offset, Math.max(0L, totalMillis))
                        : VideoPlaybackAnchor.turntable(null, normalized, Math.max(0L, totalMillis)),
                preferNative,
                decoderOverride);
        instance.setGuiConsumer(true);
        startProjectionInstance(instance);
    }

    public static ProjectorFrameSnapshot currentPreviewFrame(String sessionId) {
        VideoPlaybackInstance instance = SESSION_INSTANCES.get(sessionId);
        return instance != null ? instance.previewFrameSnapshot() : ProjectorFrameSnapshot.empty();
    }

    public static ControlConsoleVideoSnapshot currentControlConsoleVideo(BlockPos consolePos,
            boolean sourcePlaying, boolean videoExpected) {
        if (consolePos == null) {
            return null;
        }
        for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            if (!instance.containsProjector(consolePos)) {
                continue;
            }
            boolean failed = instance.hasTerminalFailure();
            ProjectorFrameSnapshot realFrame = instance.realFrameSnapshot(consolePos);
            ControlConsoleVideoStatePolicy.State state = ControlConsoleVideoStatePolicy.resolve(
                    sourcePlaying, videoExpected, failed, realFrame.hasFrame());
            ProjectorFrameSnapshot displayFrame = switch (state) {
                // 与普通视频投影仪完全一致：Iris 会捕获 BER 的多平面 YUV，
                // 因此 ACTIVE 状态也必须使用安全显示快照，避免控制台出现摩尔纹。
                case ACTIVE -> IrisShaderpackCompat.shouldApplyIrisYuvCompatibility()
                        && realFrame.yuv() ? instance.displayFrameSnapshot(consolePos) : realFrame;
                case ERROR, BUFFERING, IDLE -> controlConsolePlaceholder(state);
            };
            return new ControlConsoleVideoSnapshot(instance.sessionId(), state, displayFrame);
        }
        PendingVideoSessionRegistry.Snapshot<BlockPos> failure = PENDING_SESSIONS.findByProjector(
                PendingVideoSessionRegistry.State.FAILURE, consolePos);
        if (failure != null) {
            ControlConsoleVideoStatePolicy.State state = ControlConsoleVideoStatePolicy.resolve(
                    sourcePlaying, videoExpected, true, false);
            ProjectorFrameSnapshot frame = controlConsolePlaceholder(state);
            return new ControlConsoleVideoSnapshot(failure.sessionId(), state, frame);
        }
        PendingVideoSessionRegistry.Snapshot<BlockPos> loading = PENDING_SESSIONS.findByProjector(
                PendingVideoSessionRegistry.State.LOADING, consolePos);
        if (loading != null) {
            ControlConsoleVideoStatePolicy.State state = ControlConsoleVideoStatePolicy.resolve(
                    sourcePlaying, videoExpected, false, false);
            ProjectorFrameSnapshot frame = controlConsolePlaceholder(state);
            return new ControlConsoleVideoSnapshot(loading.sessionId(), state, frame);
        }
        ControlConsoleVideoStatePolicy.State state = ControlConsoleVideoStatePolicy.resolve(
                sourcePlaying, videoExpected, false, false);
        return new ControlConsoleVideoSnapshot(null, state, controlConsolePlaceholder(state));
    }

    private static ProjectorFrameSnapshot controlConsolePlaceholder(ControlConsoleVideoStatePolicy.State state) {
        Identifier texture = switch (state) {
            case IDLE -> CONTROL_CONSOLE_IDLE_TEXTURE;
            case BUFFERING -> CONTROL_CONSOLE_BUFFERING_TEXTURE;
            case ERROR -> CONTROL_CONSOLE_ERROR_TEXTURE;
            case ACTIVE -> throw new IllegalArgumentException("ACTIVE control-console video requires a real frame");
        };
        return new ProjectorFrameSnapshot(true, false, texture, null, null, null,
                Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA,
                LOADING_PLACEHOLDER_WIDTH, LOADING_PLACEHOLDER_HEIGHT,
                true, ControlConsoleVideoArtwork.loadingProgressOverlay(state), 0.0F);
    }

    public static boolean hasTerminalFailure(String sessionId) {
        String normalized = sessionId != null ? sessionId : "";
        VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
        return (instance != null && instance.hasTerminalFailure()) || PENDING_SESSIONS.hasFailure(normalized);
    }

    public static boolean hasGuiConsumer(String sessionId) {
        VideoPlaybackInstance instance = SESSION_INSTANCES.get(sessionId);
        return instance != null && instance.hasGuiConsumer();
    }

    public static void markPendingFailure(String sessionId, Collection<BlockPos> projectorPositions) {
        String normalized = sessionId != null ? sessionId : "";
        List<BlockPos> positions = immutablePositions(projectorPositions);
        if (normalized.isBlank() || positions.isEmpty()) {
            return;
        }
        PENDING_SESSIONS.markFailure(normalized, positions);
    }

    public static void detachControlConsoleConsumer(BlockPos consolePos) {
        if (consolePos == null) {
            return;
        }
        PENDING_SESSIONS.detachProjector(consolePos);
        detachPendingProjectionConsumer(consolePos);
        SESSION_INSTANCES.forEach(instance -> instance.removeProjector(consolePos));
        SESSION_INSTANCES.removeIf(instance -> !instance.hasVideoConsumer());
    }

    public static ResourceDiagnostics resourceDiagnostics() {
        VideoZombieCloseSupervisor.Snapshot zombies = VideoZombieCloseSupervisor.global().snapshot();
        VideoResourceDiagnosticsCollector.Snapshot snapshot = RESOURCE_DIAGNOSTICS.collect(
                SESSION_INSTANCES.instances(),
                PENDING_SESSIONS.count(PendingVideoSessionRegistry.State.LOADING),
                PENDING_SESSIONS.count(PendingVideoSessionRegistry.State.FAILURE),
                berManagedProjectorPositions.size(), zombies.activeZombies(),
                zombies.lateConvergences());
        return new ResourceDiagnostics(snapshot.instances(), snapshot.runningInstances(), snapshot.failedInstances(),
                snapshot.pendingLoading(), snapshot.pendingFailure(), snapshot.projectorReferences(),
                snapshot.berManagedProjectors(), snapshot.guiConsumers(), snapshot.activeCloseZombies(),
                snapshot.lateCloseConvergences());
    }

    public static BenchUploadResources benchUploadResources() {
        return new BenchUploadResources(LEGACY_TEXTURES.hasRgbaOrPacked(), LEGACY_TEXTURES.hasYuv(),
                MemoryResourceTracker.usage(MemoryResourceTracker.Category.TEXTURE_STAGING).currentBytes(),
                MemoryResourceTracker.usage(MemoryResourceTracker.Category.GPU_PBO).currentBytes());
    }

    public static BenchDecoderState benchDecoderState(String sessionId) {
        VideoPlaybackInstance instance = SESSION_INSTANCES.get(sessionId != null ? sessionId : "");
        return instance != null ? new BenchDecoderState(true, instance.generationForBench(),
                instance.decoderStartOffsetMillisForBench(), instance.restartStateForBench())
                : BenchDecoderState.empty();
    }

    public static long uploadFrameOnClientThreadForBench(BenchUploadFormat format, byte[] frame,
            int frameWidth, int frameHeight) {
        return switch (java.util.Objects.requireNonNull(format, "format")) {
            case RGBA -> uploadFrameSyncForBench(frame, frameWidth, frameHeight);
            case YUV420P -> uploadYuv420FrameSyncForBench(frame, frameWidth, frameHeight);
            case NV12 -> uploadNv12FrameSyncForBench(frame, frameWidth, frameHeight);
        };
    }

    public static void releaseBenchUploadResources() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            releaseTexture();
            return;
        }
        CompletableFuture<Void> released = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                releaseTexture();
                released.complete(null);
            } catch (Throwable error) {
                released.completeExceptionally(error);
            }
        });
        try {
            released.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while releasing video bench resources", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("failed to release video bench resources", error.getCause());
        }
    }

    public static void pumpPreviewFrame(String sessionId) {
        VideoPlaybackInstance instance = SESSION_INSTANCES.get(sessionId);
        if (instance != null) {
            instance.setGuiConsumer(true);
            instance.pumpUploadOnRenderThread();
        }
    }

    public static boolean hasNetworkFailure(String sessionId) {
        String normalized = sessionId != null ? sessionId : "";
        VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
        if (instance != null) {
            return instance.hasNetworkFailure();
        }
        return activeNetworkFailure && LEGACY_PREVIEW.matchesSession(normalized);
    }

    public static boolean retryNetworkFailure(String sessionId) {
        String normalized = sessionId != null ? sessionId : "";
        VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
        if (instance != null) {
            return instance.retryNetworkFailure();
        }
        return retryLegacyNetworkFailure(normalized);
    }

    public static int retryAllNetworkFailures() {
        int retried = 0;
        for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            if (instance.retryNetworkFailure()) {
                retried++;
            }
        }
        if (retryLegacyNetworkFailure(LEGACY_PREVIEW.sessionId())) {
            retried++;
        }
        return retried;
    }

    private static boolean retryLegacyNetworkFailure(String sessionId) {
        PlaybackRequest request = LEGACY_PREVIEW.request();
        if (!activeNetworkFailure || request == null
                || (sessionId != null && !sessionId.isBlank() && !LEGACY_PREVIEW.matchesSession(sessionId))) {
            return false;
        }
        long retryOffsetMillis = activeStartOffsetMillis;
        if (activeStartNanoTime > 0L) {
            retryOffsetMillis += Math.max(0L, (System.nanoTime() - activeStartNanoTime) / 1_000_000L);
        }
        stopForReplace();
        startInternal(request.videoUrl(), request.targetWidth(), request.targetHeight(), request.fps(),
                request.codecId(), request.preferNative(), request.decoderOverride(), request.sessionId(),
                retryOffsetMillis, request.totalMillis(), request.anchorPositions(), true, request.forceRgbaOutput());
        return true;
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        beginBerVisibilityFrame();
        PROJECTOR_IMMEDIATE_POSES.clear();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || SESSION_INSTANCES.isEmpty()) {
            return;
        }
        observeCameraContinuity(minecraft);
        for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            instance.pumpUploadOnRenderThread();
        }
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            cancelAllPendingProjectionStarts();
            SESSION_INSTANCES.clear();
            PENDING_SESSIONS.clear(PendingVideoSessionRegistry.State.LOADING);
            LEGACY_WORKER.requestStop();
            hasFrame = false;
            return;
        }
        if (!SESSION_INSTANCES.isEmpty()) {
            Camera camera = minecraft.gameRenderer.getMainCamera();
            SESSION_INSTANCES.removeIf(instance -> {
                if (instance.hasTerminalFailure()) {
                    // FAILED_CLOSE is an admission barrier, not an ordinary
                    // stopped instance. Keep its owner in the registry until
                    // an explicit lifecycle boundary can preserve a physical
                    // close handoff.
                    return false;
                }
                if (!instance.hasVideoConsumer()) {
                    return true;
                }
                if (!instance.hasGuiConsumer() && !instance.isWithinAudioRange(minecraft)) {
                    return true;
                }
                instance.submit(event, minecraft, camera);
                return !instance.isRunning() && !instance.hasFrame() && !instance.hasNetworkFailure();
            });
        }
        boolean renderYuvFrame = shouldRenderYuvFrame();
        DynamicTexture rgbaTexture = LEGACY_TEXTURES.rgba();
        VideoYuvTextureSet yuvTextures = LEGACY_TEXTURES.yuv();
        if (!hasFrame || (!renderYuvFrame && rgbaTexture == null) || width <= 0 || height <= 0) {
            return;
        }

        // Iris 会捕获 SubmitCustomGeometry；YUV 兼容模式改走 immediate，避免留下不可见深度状态。
        // 加载占位层仍由 VideoPlaybackInstance.submit(...) 单独提交。
        if (renderYuvFrame && shouldDrawYuvImmediateWithIris()) {
            return;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (LEGACY_PREVIEW.requiresProjector()) {
            List<VideoProjectorBlockEntity> projectors = activeVideoProjectors(minecraft);
            if (projectors.isEmpty()) {
                stop();
                return;
            }
            for (VideoProjectorBlockEntity projector : projectors) {
                if (HolographicGlassesClient.shouldHideProjectorVideos()) {
                    submitProjectorPrivacyOverlay(event, minecraft, camera, projector);
                } else if (renderYuvFrame) {
                    submitProjectorYuvGeometry(event, minecraft, camera, projector, yuvTextures);
                } else {
                    submitProjectorGeometry(event, minecraft, camera, projector);
                }
            }
            return;
        }

        VideoProjectorBlockEntity projector = activeVideoProjector(minecraft);
        if (LEGACY_PREVIEW.requiresProjector() && projector == null) {
            stop();
            return;
        }

        float scale = projector != null ? Math.abs(projector.getProjectionScale()) : 1.0F;
        float aspect = width / (float) height;
        float halfHeight = HEIGHT * scale * 0.5F;
        float halfWidth = halfHeight * aspect;

        final float p0x;
        final float p0y;
        final float p0z;
        final float p1x;
        final float p1y;
        final float p1z;
        final float p2x;
        final float p2y;
        final float p2z;
        final float p3x;
        final float p3y;
        final float p3z;

        if (WORLD_ANCHORED || LEGACY_PREVIEW.requiresProjector()) {
            if (!ensureWorldAnchor(minecraft, camera, projector)) {
                return;
            }
            Vec3 cameraPos = camera.position();
            double dx = anchorX - cameraPos.x;
            double dy = anchorY - cameraPos.y;
            double dz = anchorZ - cameraPos.z;
            if (projector != null
                    ? !isProjectorWithinRenderDistance(cameraPos, projector, anchorX, anchorY, anchorZ, aspect)
                    : dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQR) {
                return;
            }

            double yawRad = Math.toRadians(anchorYawDeg);
            double pitchRad = Math.toRadians(projector != null ? projector.getProjectionPitch() : 0.0F);
            float rightX = (float) Math.cos(yawRad);
            float rightZ = (float) Math.sin(yawRad);
            float forwardX = (float) -Math.sin(yawRad);
            float forwardZ = (float) Math.cos(yawRad);
            float upX = (float) (forwardX * Math.sin(pitchRad));
            float upY = (float) Math.cos(pitchRad);
            float upZ = (float) (forwardZ * Math.sin(pitchRad));

            float cx = (float) (anchorX - cameraPos.x);
            float cy = (float) (anchorY - cameraPos.y);
            float cz = (float) (anchorZ - cameraPos.z);
            float rx = rightX * halfWidth;
            float rz = rightZ * halfWidth;
            float ux = upX * halfHeight;
            float uy = upY * halfHeight;
            float uz = upZ * halfHeight;

            p0x = cx - rx + ux;
            p0y = cy + uy;
            p0z = cz - rz + uz;
            p1x = cx - rx - ux;
            p1y = cy - uy;
            p1z = cz - rz - uz;
            p2x = cx + rx - ux;
            p2y = cy - uy;
            p2z = cz + rz - uz;
            p3x = cx + rx + ux;
            p3y = cy + uy;
            p3z = cz + rz + uz;
        } else {
            Vector3fc forward = camera.forwardVector();
            Vector3fc left = camera.leftVector();
            Vector3fc up = camera.upVector();

            float cx = (float) (forward.x() * DISTANCE);
            float cy = (float) (forward.y() * DISTANCE);
            float cz = (float) (forward.z() * DISTANCE);
            float lx = left.x() * halfWidth;
            float ly = left.y() * halfWidth;
            float lz = left.z() * halfWidth;
            float ux = up.x() * halfHeight;
            float uy = up.y() * halfHeight;
            float uz = up.z() * halfHeight;

            p0x = cx + lx + ux;
            p0y = cy + ly + uy;
            p0z = cz + lz + uz;
            p1x = cx + lx - ux;
            p1y = cy + ly - uy;
            p1z = cz + lz - uz;
            p2x = cx - lx - ux;
            p2y = cy - ly - uy;
            p2z = cz - lz - uz;
            p3x = cx - lx + ux;
            p3y = cy - ly + uy;
            p3z = cz - lz + uz;
        }

        PoseStack poseStack = new PoseStack();
        logFirstPreviewSubmit(renderYuvFrame, width, height, camera, anchorX, anchorY, anchorZ,
                renderYuvFrame ? "preview-yuv" : "preview-rgba");
        event.getSubmitNodeCollector().submitCustomGeometry(
                poseStack,
                renderYuvFrame
                        ? yuvRenderTypeForCurrentIrisProgram(yuvTextures)
                        : YuvVideoRenderTypes.videoRgbaEntity(TEXTURE_ID),
                (pose, buffer) -> {
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            false);
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            true);
                });
    }

    @SubscribeEvent
    public static void onRenderLevelAfterTranslucentBlocks(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        if (!"after_translucent_blocks".equals(YUV_IMMEDIATE_STAGE)) {
            return;
        }
        renderInstanceProjectorYuvImmediate(event, "instance-projector-yuv-immediate-after-translucent-blocks");
        renderProjectorYuvImmediate(event, "projector-yuv-immediate-after-translucent-blocks");
        renderPreviewYuvImmediate(event, "preview-yuv-immediate-after-translucent-blocks");
    }

    @SubscribeEvent
    public static void onRenderLevelAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        if (!"after_level".equals(YUV_IMMEDIATE_STAGE)) {
            return;
        }
        renderInstanceProjectorYuvImmediate(event, "instance-projector-yuv-immediate-after-level");
        renderProjectorYuvImmediate(event, "projector-yuv-immediate-after-level");
        renderPreviewYuvImmediate(event, "preview-yuv-immediate-after-level");
    }

    private static void renderInstanceProjectorYuvImmediate(RenderLevelStageEvent event, String route) {
        if (!shouldDrawYuvImmediateWithIris() || SESSION_INSTANCES.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            instance.renderYuvImmediate(event, route);
        }
    }

    private static void renderPreviewYuvImmediate(RenderLevelStageEvent event, String route) {
        // This method is only the legacy/global preview fallback. The normal
        // synchronized projector path is rendered by VideoPlaybackInstance in
        // SubmitCustomGeometryEvent. Do not emit an Iris warning merely because
        // AfterLevel/AfterTranslucentBlocks fired: those events fire without
        // Iris and before a preview session may even exist.
        if (!shouldDrawYuvImmediateWithIris()) {
            return;
        }
        if (loggedYuvImmediateStage.compareAndSet(false, true)) {
            LOGGER.debug("Iris/YUV: 启用非投影预览 immediate 绘制，shaderpack=true，阶段='{}'，坐标模式='{}'，pose='{}'，route={}",
                    YUV_IMMEDIATE_STAGE, YUV_IMMEDIATE_COORDS, YUV_IMMEDIATE_POSE, route);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || LEGACY_PREVIEW.requiresProjector()) {
            return;
        }
        VideoYuvTextureSet yuvTextures = LEGACY_TEXTURES.yuv();
        if (!hasFrame || !shouldRenderYuvFrame() || yuvTextures == null || width <= 0 || height <= 0) {
            return;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        boolean cameraRelative = "camera_relative".equals(YUV_IMMEDIATE_COORDS)
                || "camera-relative".equals(YUV_IMMEDIATE_COORDS)
                || "relative".equals(YUV_IMMEDIATE_COORDS);
        PreviewQuad quad = computePreviewQuad(minecraft, camera, null, width, height, cameraRelative, true);
        if (quad == null) {
            return;
        }

        RenderType renderType = yuvRenderTypeForCurrentIrisProgram(yuvTextures);
        BufferBuilder builder = Tesselator.getInstance().begin(renderType.mode(), renderType.format());
        PoseStack poseStack = "identity".equals(YUV_IMMEDIATE_POSE) ? new PoseStack() : event.getPoseStack();
        PoseStack.Pose pose = poseStack.last();
        emitQuad(builder, pose, quad.p0x(), quad.p0y(), quad.p0z(), quad.p1x(), quad.p1y(), quad.p1z(),
                quad.p2x(), quad.p2y(), quad.p2z(), quad.p3x(), quad.p3y(), quad.p3z(), false);
        emitQuad(builder, pose, quad.p0x(), quad.p0y(), quad.p0z(), quad.p1x(), quad.p1y(), quad.p1z(),
                quad.p2x(), quad.p2y(), quad.p2z(), quad.p3x(), quad.p3y(), quad.p3z(), true);
        MeshData mesh = builder.build();
        if (mesh == null) {
            return;
        }

        logFirstPreviewSubmit(true, width, height, camera, anchorX, anchorY, anchorZ,
                route);
        if (YUV_DEBUG_LOG) {
            logFirstImmediateQuad(quad, camera, cameraRelative, true);
        }
        drawWithEventModelView(renderType, mesh, event);
    }

    static boolean shouldDrawYuvImmediateWithIris() {
        return IrisShaderpackCompat.shouldDrawYuvImmediate();
    }

    private static void renderProjectorYuvImmediate(RenderLevelStageEvent event, String route) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !LEGACY_PREVIEW.requiresProjector()) {
            return;
        }
        VideoYuvTextureSet yuvTextures = LEGACY_TEXTURES.yuv();
        if (!hasFrame || !shouldRenderYuvFrame() || yuvTextures == null || width <= 0 || height <= 0) {
            return;
        }
        if (!shouldDrawYuvImmediateWithIris()) {
            return;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        List<VideoProjectorBlockEntity> projectors = activeVideoProjectors(minecraft);
        if (projectors.isEmpty()) {
            stop();
            return;
        }
        boolean cameraRelative = "camera_relative".equals(YUV_IMMEDIATE_COORDS)
                || "camera-relative".equals(YUV_IMMEDIATE_COORDS)
                || "relative".equals(YUV_IMMEDIATE_COORDS);
        if (!loggedProjectorYuvImmediate) {
            loggedProjectorYuvImmediate = true;
            LOGGER.debug("Iris/YUV: shaderpack 下投影仪 YUV 改用 immediate 绘制阶段 '{}'，坐标模式 '{}'，route={}",
                    YUV_IMMEDIATE_STAGE, YUV_IMMEDIATE_COORDS, route);
        }

        for (VideoProjectorBlockEntity projector : projectors) {
            if (HolographicGlassesClient.shouldHideProjectorVideos()) {
                drawProjectorPrivacyOverlayImmediate(event, minecraft, camera, projector, route);
            } else {
                drawProjectorYuvImmediate(event, minecraft, camera, projector, yuvTextures, route, cameraRelative);
            }
        }
    }

    static boolean drawProjectorYuvImmediate(RenderLevelStageEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, VideoYuvTextureSet textures, String route) {
        boolean cameraRelative = "camera_relative".equals(YUV_IMMEDIATE_COORDS)
                || "camera-relative".equals(YUV_IMMEDIATE_COORDS)
                || "relative".equals(YUV_IMMEDIATE_COORDS);
        return drawProjectorYuvImmediate(event, minecraft, camera, projector, textures, route, cameraRelative);
    }

    public static void captureProjectorImmediatePose(String sessionId, BlockPos projectorPos, Matrix4f pose,
            float halfHeight) {
        PlaybackSessionId.parse(sessionId).ifPresent(playbackSessionId ->
                captureProjectorImmediatePose(playbackSessionId, projectorPos, pose, halfHeight));
    }

    public static void captureProjectorImmediatePose(PlaybackSessionId playbackSessionId, BlockPos projectorPos,
            Matrix4f pose, float halfHeight) {
        if (playbackSessionId == null || projectorPos == null || pose == null
                || halfHeight <= 0.0F) {
            return;
        }
        PROJECTOR_IMMEDIATE_POSES.put(new ProjectorImmediateKey(playbackSessionId, projectorPos.immutable()),
                new ProjectorImmediatePose(new Matrix4f(pose), halfHeight));
    }

    public static void captureProjectorImmediatePose(String sessionId, BlockPos projectorPos, Matrix4f pose,
            float halfHeight, float opacity) {
        if (opacity > 0.0F) {
            captureProjectorImmediatePose(sessionId, projectorPos, pose, halfHeight);
        }
    }

    public static void captureProjectorImmediatePose(PlaybackSessionId playbackSessionId, BlockPos projectorPos,
            Matrix4f pose, float halfHeight, float opacity) {
        if (opacity > 0.0F) {
            captureProjectorImmediatePose(playbackSessionId, projectorPos, pose, halfHeight);
        }
    }

    static boolean drawCapturedProjectorYuvImmediate(RenderLevelStageEvent event, String sessionId,
            BlockPos projectorPos, VideoYuvTextureSet textures, String route) {
        PlaybackSessionId playbackSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (event == null || playbackSessionId == null || projectorPos == null || textures == null
                || textures.width() <= 0 || textures.height() <= 0) {
            return false;
        }
        ProjectorImmediatePose captured = PROJECTOR_IMMEDIATE_POSES.remove(
                new ProjectorImmediateKey(playbackSessionId, projectorPos));
        if (captured == null) {
            return false;
        }

        float halfWidth = captured.halfHeight() * textures.width() / (float) textures.height();
        PreviewQuad quad = transformedLocalQuad(captured.pose(), halfWidth, captured.halfHeight());
        RenderType renderType = yuvRenderTypeForCurrentIrisProgram(textures);
        BufferBuilder builder = Tesselator.getInstance().begin(renderType.mode(), renderType.format());
        PoseStack.Pose identityPose = new PoseStack().last();
        emitQuad(builder, identityPose, quad.p0x(), quad.p0y(), quad.p0z(), quad.p1x(), quad.p1y(), quad.p1z(),
                quad.p2x(), quad.p2y(), quad.p2z(), quad.p3x(), quad.p3y(), quad.p3z(), false);
        emitQuad(builder, identityPose, quad.p0x(), quad.p0y(), quad.p0z(), quad.p1x(), quad.p1y(), quad.p1z(),
                quad.p2x(), quad.p2y(), quad.p2z(), quad.p3x(), quad.p3y(), quad.p3z(), true);
        MeshData mesh = builder.build();
        if (mesh == null) {
            return false;
        }
        if (YUV_DEBUG_LOG) {
            LOGGER.debug("Iris/YUV: 使用 BER 当帧矩阵绘制投影视频，session={}, projector={}, route={}",
                    sessionId, projectorPos, route);
        }
        drawWithEventModelView(renderType, mesh, event);
        return true;
    }

    protected static boolean drawProjectorYuvImmediate(RenderLevelStageEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, VideoYuvTextureSet textures, String route, boolean cameraRelative) {
        if (projector == null || textures == null || textures.width() <= 0 || textures.height() <= 0) {
            return false;
        }
        PreviewQuad quad = computePreviewQuad(minecraft, camera, projector,
                textures.width(), textures.height(), cameraRelative, true);
        if (quad == null) {
            return false;
        }
        RenderType renderType = yuvRenderTypeForCurrentIrisProgram(textures);
        BufferBuilder builder = Tesselator.getInstance().begin(renderType.mode(), renderType.format());
        PoseStack poseStack = "identity".equals(YUV_IMMEDIATE_POSE) ? new PoseStack() : event.getPoseStack();
        PoseStack.Pose pose = poseStack.last();
        emitQuad(builder, pose, quad.p0x(), quad.p0y(), quad.p0z(), quad.p1x(), quad.p1y(), quad.p1z(),
                quad.p2x(), quad.p2y(), quad.p2z(), quad.p3x(), quad.p3y(), quad.p3z(), false);
        emitQuad(builder, pose, quad.p0x(), quad.p0y(), quad.p0z(), quad.p1x(), quad.p1y(), quad.p1z(),
                quad.p2x(), quad.p2y(), quad.p2z(), quad.p3x(), quad.p3y(), quad.p3z(), true);
        MeshData mesh = builder.build();
        if (mesh == null) {
            return false;
        }
        logFirstPreviewSubmit(true, textures.width(), textures.height(), camera,
                projector.getBlockPos().getX() + 0.5D + projector.getProjectionDistanceX(),
                projector.getBlockPos().getY() + projector.getProjectionHeight(),
                projector.getBlockPos().getZ() + 0.5D + projector.getProjectionDistanceZ(), route);
        drawWithEventModelView(renderType, mesh, event);
        return true;
    }

}
