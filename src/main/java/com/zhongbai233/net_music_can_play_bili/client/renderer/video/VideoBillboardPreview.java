package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureFlags;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.RenderVertexUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.joml.Matrix4fStack;
import org.joml.Vector3fc;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
public final class VideoBillboardPreview {
    public static ProjectorFrameSnapshot currentProjectorFrame(BlockPos projectorPos) {
        if (projectorPos != null) {
            for (VideoPlaybackInstance instance : INSTANCES.values()) {
                ProjectorFrameSnapshot snapshot = instance.frameSnapshot(projectorPos);
                if (snapshot.hasFrame()) {
                    return snapshot;
                }
            }
        }
        if (!hasFrame || width <= 0 || height <= 0) {
            return ProjectorFrameSnapshot.empty();
        }
        if (projectorPos != null && !activeProjectorPositions.isEmpty()
                && !activeProjectorPositions.contains(projectorPos)) {
            return ProjectorFrameSnapshot.empty();
        }
        boolean yuv = shouldRenderYuvFrame() && yuvTextureSet != null;
        return new ProjectorFrameSnapshot(true, yuv, TEXTURE_ID, yuv ? yuvTextureSet.yId() : null,
                yuv ? yuvTextureSet.uId() : null, yuv ? yuvTextureSet.vId() : null,
                yuv ? yuvTextureSet.format() : Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, width, height, false,
                false);
    }

    public static ProjectorFrameSnapshot currentProjectorDisplayFrame(BlockPos projectorPos) {
        if (projectorPos != null) {
            for (VideoPlaybackInstance instance : INSTANCES.values()) {
                ProjectorFrameSnapshot snapshot = instance.displayFrameSnapshot(projectorPos);
                if (snapshot.hasFrame()) {
                    return snapshot;
                }
            }
        }
        return currentProjectorFrame(projectorPos);
    }

    public static ProjectorFrameSnapshot currentTurntableFrame(BlockPos turntablePos) {
        if (turntablePos != null) {
            for (VideoPlaybackInstance instance : INSTANCES.values()) {
                ProjectorFrameSnapshot snapshot = instance.turntableFrameSnapshot(turntablePos);
                if (snapshot.hasFrame()) {
                    return snapshot;
                }
            }
        }
        return ProjectorFrameSnapshot.empty();
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "dynamic/bili_video_preview");
    private static final Identifier YUV_TEXTURE_Y_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "dynamic/bili_video_preview_y");
    private static final Identifier YUV_TEXTURE_U_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "dynamic/bili_video_preview_u");
    private static final Identifier YUV_TEXTURE_V_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "dynamic/bili_video_preview_v");
    private static final Identifier PACKED_BENCH_TEXTURE_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "dynamic/bili_video_packed_bench");
    private static final Identifier LOADING_PROGRESS_FRAME_TEXTURE = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/video_loading/progress_frame_204x10.png");
    private static final Identifier LOADING_PROGRESS_SEGMENT_TEXTURE = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/video_loading/progress_segment_42x6.png");
    private static final int LOADING_PLACEHOLDER_WIDTH = 320;
    private static final int LOADING_PLACEHOLDER_HEIGHT = 180;
    private static final int LOADING_PROGRESS_X = 58;
    private static final int LOADING_PROGRESS_Y = 126;
    private static final int LOADING_PROGRESS_W = 204;
    private static final int LOADING_PROGRESS_H = 10;
    private static final int LOADING_PROGRESS_SEGMENT_W = 42;
    private static final int LOADING_PROGRESS_SEGMENT_H = 6;
    static final int MIN_ADAPTIVE_WIDTH = VideoFeatureFlags.advancedInt("bili.video.adaptive.min_width", 640);
    static final long ADAPTIVE_FRAME_BUDGET_NS = VideoFeatureFlags
            .advancedLong("bili.video.adaptive.frame_budget_ms", 12L) * 1_000_000L;
    static final int ADAPTIVE_BAD_FRAME_THRESHOLD = VideoFeatureFlags
            .advancedInt("bili.video.adaptive.bad_frames", 45);
    private static final boolean PROTECT_GAME_FPS = VideoFeatureFlags
            .advancedBoolean("bili.video.protect_game_fps", true);
    private static final int PROTECTED_4K_FPS = VideoFeatureFlags.advancedInt("bili.video.protect.4k_fps", 3);
    private static final int PROTECTED_8K_FPS = VideoFeatureFlags.advancedInt("bili.video.protect.8k_fps", 1);
    static final int MAX_CATCH_UP_DROPS_PER_TICK = VideoFeatureFlags.advancedInt(
            "bili.video.sync.max_drop_frames", 12);
    private static final double DISTANCE = 3.0D;
    private static final float HEIGHT = 1.35F;
    private static final boolean CPU_BARS = VideoFeatureFlags.advancedBoolean("ncpb.video.cpu_bars", false);
    private static final boolean WORLD_ANCHORED = Boolean.parseBoolean(
            System.getProperty("ncpb.video.world_anchor", "true"));
    private static final String YUV_IMMEDIATE_STAGE = System.getProperty(
            "ncpb.video.yuv.immediate_stage", "after_level").trim().toLowerCase(java.util.Locale.ROOT);
    private static final String YUV_IMMEDIATE_COORDS = System.getProperty(
            "ncpb.video.yuv.immediate_coords", "camera_relative").trim().toLowerCase(java.util.Locale.ROOT);
    private static final String YUV_IMMEDIATE_POSE = System.getProperty(
            "ncpb.video.yuv.immediate_pose", "identity").trim().toLowerCase(java.util.Locale.ROOT);
    private static final boolean YUV_DEBUG_LOG = Boolean.getBoolean(
            "ncpb.video.yuv.debug_log");
    private static final double WORLD_ANCHOR_DISTANCE = Double.parseDouble(
            System.getProperty("ncpb.video.world_anchor.distance", "6.0"));
    static final double AUDIO_SYNC_RANGE_SQR = Math.pow(Double.parseDouble(
            System.getProperty("ncpb.video.turntable.sync_range", "96.0")), 2.0);
    private static final double VIEW_DOT_THRESHOLD = Double.parseDouble(
            System.getProperty("ncpb.video.render.view_dot_threshold", "0.12"));
    private static final boolean VIEW_OCCLUSION_CHECK = Boolean.parseBoolean(
            System.getProperty("ncpb.video.render.occlusion_check", "true"));
    private static final long VIEW_OCCLUSION_CACHE_NANOS = Long.getLong(
            "bili.video.render.occlusion_cache_ms", 150L) * 1_000_000L;
    private static final double VIEW_SAMPLE_EDGE_SCALE = Double.parseDouble(
            System.getProperty("ncpb.video.render.visibility_sample_edge_scale", "0.86"));
    private static final double MAX_RENDER_DISTANCE_SQR = Math.pow(Double.parseDouble(
            System.getProperty("ncpb.video.max_render_distance", "64.0")), 2.0);
    static final String RENDER_BACKEND = System.getProperty("ncpb.video.render.backend", "nv12")
            .trim().toLowerCase(java.util.Locale.ROOT);
    static final boolean NV12_DECODE_BACKEND = RENDER_BACKEND.equals("nv12")
            || RENDER_BACKEND.equals("nv12_shader")
            || RENDER_BACKEND.equals("yuv");
    static final boolean YUV420_DECODE_BACKEND = RENDER_BACKEND.equals("yuv420")
            || RENDER_BACKEND.equals("yuv420_shader")
            || RENDER_BACKEND.equals("yuv420_cpu");
    static final boolean YUV_DECODE_BACKEND = NV12_DECODE_BACKEND || YUV420_DECODE_BACKEND;
    static final boolean CUSTOM_YUV_SHADER_BACKEND = RENDER_BACKEND.equals("yuv")
            || RENDER_BACKEND.equals("yuv420")
            || RENDER_BACKEND.equals("yuv420_shader")
            || RENDER_BACKEND.equals("nv12")
            || RENDER_BACKEND.equals("nv12_shader");
    private static final boolean YUV_UPLOAD_PLANES = Boolean.parseBoolean(
            System.getProperty("ncpb.video.yuv.upload_planes", "true"));

    private static final Map<String, VideoPlaybackInstance> INSTANCES = new ConcurrentHashMap<>();

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final AtomicBoolean loggedIrisYuvRenderType = new AtomicBoolean(false);

    public record ProjectorFrameSnapshot(boolean hasFrame, boolean yuv, Identifier rgbaTexture, Identifier yTexture,
            Identifier uTexture, Identifier vTexture, Fmp4NativeVideoDecoder.DecodedFrame.Format format, int width,
            int height, boolean emissiveRgba, boolean loadingProgressOverlay) {
        public static ProjectorFrameSnapshot empty() {
            return new ProjectorFrameSnapshot(false, false, null, null, null, null,
                    Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, 0, 0, false, false);
        }
    }

    private static final AtomicBoolean loggedYuvImmediateStage = new AtomicBoolean(false);
    private static final AtomicLong decodeGeneration = new AtomicLong();
    private static volatile boolean running;
    private static volatile int width;
    private static volatile int height;
    private static volatile int activeFps;
    private static volatile long activeStartOffsetMillis;
    private static volatile long activeStartNanoTime;
    private static volatile boolean hasFrame;
    private static DynamicTexture texture;
    private static VideoYuvTextureSet yuvTextureSet;
    private static DynamicTexture packedBenchTexture;
    private static volatile boolean anchorInitialized;
    private static volatile double anchorX;
    private static volatile double anchorY;
    private static volatile double anchorZ;
    private static volatile float anchorYawDeg;
    private static volatile boolean cameraContinuityInitialized;
    private static volatile double lastCameraX;
    private static volatile double lastCameraY;
    private static volatile double lastCameraZ;
    private static volatile String lastCameraDimension;
    private static final double CAMERA_TELEPORT_RESET_DISTANCE_SQR = Math.pow(Double.parseDouble(
            System.getProperty("ncpb.video.projector.teleport_reset_distance", "16.0")), 2.0D);
    private static volatile boolean firstImmediateQuadLogged;
    private static volatile boolean loggedProjectorYuvImmediate;
    private static volatile Thread decodeThread;
    private static volatile AutoCloseable activeDecoder;
    private static volatile String activeSessionId = "";
    private static volatile BlockPos activeProjectorPos;
    private static final Set<BlockPos> activeProjectorPositions = new CopyOnWriteArraySet<>();
    private static final Set<BlockPos> berRenderedProjectorPositions = new CopyOnWriteArraySet<>();
    private static final ConcurrentHashMap<BlockPos, VisibilitySample> PROJECTOR_VISIBILITY_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean activeRequiresProjector;
    private static volatile PlaybackRequest activeRequest;
    private static volatile boolean firstPreviewSubmitLogged;

    private VideoBillboardPreview() {
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
        String normalized = sessionId != null ? sessionId : "";
        if (normalized.isBlank()) {
            startInternal(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride,
                    normalized, Math.max(0L, startOffsetMillis), Math.max(0L, totalMillis), null, true, true);
            return;
        }
        long offset = Math.max(0L, startOffsetMillis);
        VideoPlaybackInstance existing = INSTANCES.get(normalized);
        if (existing != null && existing.isRunningAtOffset(offset, 250L)) {
            existing.setGuiConsumer(true);
            return;
        }
        if (existing != null) {
            existing.stop();
        }
        VideoPlaybackInstance instance = new VideoPlaybackInstance(videoUrl, targetWidth, targetHeight, fps, codecId,
                normalized, offset, Math.max(0L, totalMillis), List.of(),
                sourceId != null
                        ? new PreviewVideoPlaybackAnchor(sourceId, normalized, offset, Math.max(0L, totalMillis))
                        : VideoPlaybackAnchor.turntable(null, normalized, Math.max(0L, totalMillis)),
                preferNative,
                decoderOverride);
        instance.setGuiConsumer(true);
        INSTANCES.put(normalized, instance);
        instance.start();
    }

    public static ProjectorFrameSnapshot currentPreviewFrame(String sessionId) {
        VideoPlaybackInstance instance = INSTANCES.get(sessionId != null ? sessionId : "");
        return instance != null ? instance.previewFrameSnapshot() : ProjectorFrameSnapshot.empty();
    }

    public static void pumpPreviewFrame(String sessionId) {
        VideoPlaybackInstance instance = INSTANCES.get(sessionId != null ? sessionId : "");
        if (instance != null) {
            instance.setGuiConsumer(true);
            instance.pumpUploadOnRenderThread();
        }
    }

    public static void startSynced(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, BlockPos anchorPos, String decoderOverride) {
        startSynced(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, 0L, anchorPos,
                false, decoderOverride);
    }

    public static void startSynced(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, BlockPos anchorPos, boolean preferNative,
            String decoderOverride) {
        startSynced(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, 0L, anchorPos,
                preferNative, decoderOverride);
    }

    public static void startSynced(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, BlockPos anchorPos, boolean preferNative,
            String decoderOverride) {
        startSynced(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, totalMillis,
                anchorPos != null ? List.of(anchorPos) : List.of(), preferNative, decoderOverride);
    }

    public static void startSynced(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> anchorPositions,
            boolean preferNative, String decoderOverride) {
        startSynced(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, totalMillis,
                anchorPositions, (BlockPos) null, preferNative, decoderOverride);
    }

    public static void startSynced(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> anchorPositions,
            BlockPos turntablePos, boolean preferNative, String decoderOverride) {
        VideoPlaybackAnchor anchor = VideoPlaybackAnchor.turntable(turntablePos, sessionId, Math.max(0L, totalMillis));
        startSynced(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, totalMillis,
                anchorPositions, anchor, preferNative, decoderOverride);
    }

    static void startSynced(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> anchorPositions,
            VideoPlaybackAnchor anchor, boolean preferNative, String decoderOverride) {
        if (sessionId != null && !sessionId.isBlank()) {
            startOrUpdateInstance(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis,
                    totalMillis, anchorPositions, anchor, preferNative, decoderOverride);
            return;
        }
        startInternal(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, sessionId,
                startOffsetMillis, totalMillis, anchorPositions, true);
    }

    private static void startOrUpdateInstance(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> anchorPositions,
            VideoPlaybackAnchor anchor, boolean preferNative, String decoderOverride) {
        List<BlockPos> projectors = immutablePositions(anchorPositions);
        boolean hasHolographicConsumer = hasHolographicTurntableConsumer(anchor);
        if (projectors.isEmpty() && !hasHolographicConsumer) {
            stopIfSession(sessionId);
            return;
        }
        VideoPlaybackInstance existing = INSTANCES.get(sessionId);
        long normalizedOffset = Math.max(0L, startOffsetMillis);
        if (existing != null && existing.canChaseToOffset(normalizedOffset)) {
            existing.replaceProjectors(projectors);
            return;
        }
        if (existing != null) {
            existing.stop();
        }
        VideoPlaybackInstance instance = new VideoPlaybackInstance(videoUrl, targetWidth, targetHeight, fps, codecId,
                sessionId,
                normalizedOffset, Math.max(0L, totalMillis), projectors, anchor, preferNative, decoderOverride);
        INSTANCES.put(sessionId, instance);
        instance.start();
    }

    private static void startInternal(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, String sessionId, long startOffsetMillis,
            long totalMillis, Collection<BlockPos> anchorPositions, boolean catchUpDropsEnabled) {
        startInternal(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, sessionId,
                startOffsetMillis, totalMillis, anchorPositions, catchUpDropsEnabled, false);
    }

    private static void startInternal(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, String sessionId, long startOffsetMillis,
            long totalMillis, Collection<BlockPos> anchorPositions, boolean catchUpDropsEnabled,
            boolean forceRgbaOutput) {
        if (videoUrl == null || videoUrl.isBlank()) {
            return;
        }
        String normalizedSession = sessionId != null ? sessionId : "";
        long normalizedOffset = Math.max(0L, startOffsetMillis);
        if (!normalizedSession.isBlank() && running && normalizedSession.equals(activeSessionId)) {
            replaceActiveProjectors(anchorPositions);
            if (isSessionRunningAtOffset(normalizedSession, normalizedOffset)) {
                return;
            }
            stopForReplace();
        }
        if (!normalizedSession.isBlank() && running) {
            stopForReplace();
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }

        running = true;
        hasFrame = false;
        width = Math.max(1, targetWidth);
        height = Math.max(1, targetHeight);
        int protectedFps = protectedUploadFps(targetWidth, targetHeight, fps);
        if (protectedFps < Math.max(1, fps)) {
            LOGGER.warn("视频分辨率过高，限制上传/显示帧率以保护游戏 FPS: {}x{} @ {}fps -> {}fps。可用 -Dbili.video.protect_game_fps=false 关闭",
                    targetWidth, targetHeight, Math.max(1, fps), protectedFps);
        }
        activeFps = protectedFps;
        activeStartOffsetMillis = normalizedOffset;
        activeStartNanoTime = System.nanoTime();
        activeSessionId = normalizedSession;
        replaceActiveProjectors(anchorPositions);
        activeProjectorPos = activeProjectorPositions.stream().findFirst().orElse(null);
        activeRequiresProjector = !normalizedSession.isBlank() && !activeProjectorPositions.isEmpty();
        activeRequest = new PlaybackRequest(videoUrl, targetWidth, targetHeight, protectedFps, codecId, preferNative,
                decoderOverride, normalizedSession, startOffsetMillis, totalMillis, activeProjectorPositions,
                forceRgbaOutput);
        long generation = decodeGeneration.incrementAndGet();
        if (activeProjectorPos != null) {
            anchorX = activeProjectorPos.getX() + 0.5D;
            anchorY = activeProjectorPos.getY() + 1.8D;
            anchorZ = activeProjectorPos.getZ() + 0.5D;
            anchorYawDeg = 0.0F;
            anchorInitialized = true;
        } else {
            anchorInitialized = false;
        }

        Thread thread = new Thread(
                () -> decodeLoop(videoUrl, targetWidth, targetHeight, protectedFps, codecId, preferNative,
                        decoderOverride, startOffsetMillis, totalMillis, generation, catchUpDropsEnabled,
                        forceRgbaOutput),
                "bili-video-billboard-preview");
        thread.setDaemon(true);
        decodeThread = thread;
        thread.start();
        LOGGER.info("视频 billboard 预览已启动: {}x{} @ {}fps, renderBackend={}, decodeFormat={}, catchUpDrops={}", width,
                height,
                activeFps, RENDER_BACKEND, YUV_DECODE_BACKEND
                        ? (isCustomYuvShaderAvailable() ? yuvDecodeFormat().name() + "→RGB(shader)"
                                : yuvDecodeFormat().name() + "→RGBA(cpu/iris-fallback)")
                        : "RGBA",
                catchUpDropsEnabled);
    }

    private static int protectedUploadFps(int frameWidth, int frameHeight, int requestedFps) {
        int fps = Math.max(1, requestedFps);
        if (!PROTECT_GAME_FPS) {
            return fps;
        }
        long pixels = (long) Math.max(1, frameWidth) * Math.max(1, frameHeight);
        if (pixels >= 7000L * 4000L) {
            return Math.min(fps, Math.max(1, PROTECTED_8K_FPS));
        }
        if (pixels >= 3000L * 1600L) {
            return Math.min(fps, Math.max(1, PROTECTED_4K_FPS));
        }
        return fps;
    }

    public static void startTestPattern(int targetWidth, int targetHeight, int fps) {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        running = true;
        hasFrame = false;
        anchorInitialized = false;
        width = Math.max(1, targetWidth);
        height = Math.max(1, targetHeight);
        activeFps = Math.max(1, fps);
        activeStartOffsetMillis = 0L;
        activeStartNanoTime = System.nanoTime();

        Thread thread = new Thread(() -> decodeTestPatternLoop(targetWidth, targetHeight, fps),
                "bili-video-billboard-test-pattern");
        thread.setDaemon(true);
        decodeThread = thread;
        thread.start();
        LOGGER.info("视频 billboard 本地测试图预览已启动: {}x{} @ {}fps, pixelMode={}", targetWidth,
                targetHeight, fps, VideoFrameUploader.pixelMode());
    }

    public static void stop() {
        for (VideoPlaybackInstance instance : INSTANCES.values()) {
            instance.stop();
        }
        INSTANCES.clear();
        running = false;
        started.set(false);
        hasFrame = false;
        anchorInitialized = false;
        activeFps = 0;
        activeStartOffsetMillis = 0L;
        activeStartNanoTime = 0L;
        activeSessionId = "";
        activeProjectorPos = null;
        activeProjectorPositions.clear();
        berRenderedProjectorPositions.clear();
        PROJECTOR_VISIBILITY_CACHE.clear();
        activeRequest = null;
        activeRequiresProjector = false;
        resetLocalRenderAnchors();
        decodeGeneration.incrementAndGet();
        closeActiveDecoderAsync();
        Thread thread = decodeThread;
        if (thread != null) {
            thread.interrupt();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            releaseTexture();
        } else {
            minecraft.execute(VideoBillboardPreview::releaseTexture);
        }
    }

    private static void stopForReplace() {
        running = false;
        started.set(false);
        hasFrame = false;
        activeRequiresProjector = false;
        activeProjectorPositions.clear();
        activeFps = 0;
        activeStartOffsetMillis = 0L;
        activeStartNanoTime = 0L;
        decodeGeneration.incrementAndGet();
        closeActiveDecoderAsync();
        Thread thread = decodeThread;
        if (thread != null) {
            thread.interrupt();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            releaseTexture();
        } else {
            minecraft.execute(VideoBillboardPreview::releaseTexture);
        }
    }

    public static void stopIfSession(String sessionId) {
        String normalized = sessionId != null ? sessionId : "";
        ModernTurntableVideoClient.forgetSession(normalized);
        VideoPlaybackInstance instance = INSTANCES.remove(normalized);
        if (instance != null) {
            instance.stop();
        }
        if (!normalized.isBlank() && normalized.equals(activeSessionId)) {
            stop();
        }
    }

    public static void stopIfProjector(BlockPos projectorPos) {
        if (projectorPos == null) {
            return;
        }
        berRenderedProjectorPositions.remove(projectorPos);
        PROJECTOR_VISIBILITY_CACHE.remove(projectorPos);
        INSTANCES.values().forEach(instance -> instance.removeProjector(projectorPos));
        INSTANCES.entrySet().removeIf(entry -> {
            if (entry.getValue().hasProjectors()) {
                return false;
            }
            if (entry.getValue().hasVideoConsumer()) {
                return false;
            }
            entry.getValue().stop();
            return true;
        });
        activeProjectorPositions.remove(projectorPos);
        if (projectorPos.equals(activeProjectorPos)) {
            activeProjectorPos = activeProjectorPositions.stream().findFirst().orElse(null);
        }
        if (activeRequiresProjector && activeProjectorPositions.isEmpty()) {
            stop();
        }
    }

    public static void attachProjectorToTurntable(BlockPos turntablePos, BlockPos projectorPos) {
        if (turntablePos == null || projectorPos == null) {
            return;
        }
        berRenderedProjectorPositions.add(projectorPos.immutable());
        for (VideoPlaybackInstance instance : INSTANCES.values()) {
            if (instance.isForTurntable(turntablePos)) {
                instance.addProjector(projectorPos);
            }
        }
    }

    static boolean isProjectorRenderedByBer(BlockPos projectorPos) {
        return projectorPos != null && berRenderedProjectorPositions.contains(projectorPos);
    }

    public static boolean hasSessionForTurntable(BlockPos turntablePos) {
        if (turntablePos == null) {
            return false;
        }
        for (VideoPlaybackInstance instance : INSTANCES.values()) {
            if (instance.isForTurntable(turntablePos)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasSessionForTurntable(BlockPos turntablePos, String sessionId) {
        if (turntablePos == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        for (VideoPlaybackInstance instance : INSTANCES.values()) {
            if (instance.isForTurntable(turntablePos) && instance.isSession(sessionId)) {
                return true;
            }
        }
        return running && started.get() && sessionId.equals(activeSessionId);
    }

    public static boolean isSessionRunning(String sessionId) {
        String normalized = sessionId != null ? sessionId : "";
        VideoPlaybackInstance instance = INSTANCES.get(normalized);
        if (instance != null) {
            return instance.isRunning();
        }
        return running && started.get() && !normalized.isBlank() && normalized.equals(activeSessionId);
    }

    public static void updateSessionProjectors(String sessionId, Collection<BlockPos> projectorPositions) {
        String normalized = sessionId != null ? sessionId : "";
        if (normalized.isBlank()) {
            return;
        }
        VideoPlaybackInstance instance = INSTANCES.get(normalized);
        if (instance != null) {
            instance.replaceProjectors(projectorPositions);
            return;
        }
        if (running && normalized.equals(activeSessionId)) {
            replaceActiveProjectors(projectorPositions);
            activeProjectorPos = activeProjectorPositions.stream().findFirst().orElse(null);
            activeRequiresProjector = !activeProjectorPositions.isEmpty();
        }
    }

    private static boolean hasHolographicTurntableConsumer(VideoPlaybackAnchor anchor) {
        if (anchor == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return false;
        }
        for (var binding : HolographicGlassesClient.screenBindings()) {
            if (binding.source() != null && binding.source().isTurntable()
                    && minecraft.level.dimension().equals(binding.source().dimension())
                    && anchor.isForTurntable(binding.source().pos())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSessionRunningAtOffset(String sessionId, long requestedOffsetMillis) {
        return isSessionRunningAtOffset(sessionId, requestedOffsetMillis, 1_500L);
    }

    public static boolean isSessionRunningAtOffset(String sessionId, long requestedOffsetMillis,
            long toleranceMillis) {
        if (!isSessionRunning(sessionId)) {
            return false;
        }
        VideoPlaybackInstance instance = INSTANCES.get(sessionId != null ? sessionId : "");
        if (instance != null) {
            return instance.isRunningAtOffset(Math.max(0L, requestedOffsetMillis), Math.max(0L, toleranceMillis));
        }
        long expectedOffset = activeStartOffsetMillis
                + Math.max(0L, (System.nanoTime() - activeStartNanoTime) / 1_000_000L);
        return Math.abs(expectedOffset - Math.max(0L, requestedOffsetMillis)) < Math.max(0L, toleranceMillis);
    }

    public static boolean canSessionChaseToOffset(String sessionId, long requestedOffsetMillis) {
        if (!isSessionRunning(sessionId)) {
            return false;
        }
        VideoPlaybackInstance instance = INSTANCES.get(sessionId != null ? sessionId : "");
        if (instance != null) {
            return instance.canChaseToOffset(Math.max(0L, requestedOffsetMillis));
        }
        return isSessionRunningAtOffset(sessionId, requestedOffsetMillis,
                Long.getLong("ncpb.video.pipeline.chase_window_ms", 10_000L));
    }

    public static boolean isSessionWaitingForFirstFrame(String sessionId) {
        String normalized = sessionId != null ? sessionId : "";
        if (normalized.isBlank()) {
            return false;
        }
        VideoPlaybackInstance instance = INSTANCES.get(normalized);
        if (instance != null) {
            return instance.isRunning() && !instance.hasFrame();
        }
        return running && started.get() && normalized.equals(activeSessionId) && !hasFrame;
    }

    public static VideoStatus getStatusForProjector(BlockPos projectorPos) {
        for (VideoPlaybackInstance instance : INSTANCES.values()) {
            if (instance.containsProjector(projectorPos)) {
                return instance.status();
            }
        }
        if (!running || !started.get()) {
            return VideoStatus.empty();
        }
        if (projectorPos != null && !activeProjectorPositions.isEmpty()
                && !activeProjectorPositions.contains(projectorPos)) {
            return VideoStatus.empty();
        }
        return new VideoStatus(width, height, activeFps, hasFrame, !activeSessionId.isBlank());
    }

    public static VideoSyncStatus getSyncStatus(String sessionId) {
        String normalized = sessionId != null ? sessionId : "";
        VideoPlaybackInstance instance = INSTANCES.get(normalized);
        if (instance != null) {
            return new VideoSyncStatus(instance.isRunning(), instance.hasFrame(), instance.mediaMillis(),
                    instance.queuedMediaMillis(), instance.status().width(), instance.status().height(),
                    instance.status().fps());
        }
        if (running && started.get() && !normalized.isBlank() && normalized.equals(activeSessionId)) {
            long mediaMillis = activeStartOffsetMillis
                    + Math.max(0L, (System.nanoTime() - activeStartNanoTime) / 1_000_000L);
            return new VideoSyncStatus(true, hasFrame, mediaMillis, -1L, width, height, activeFps);
        }
        return VideoSyncStatus.empty();
    }

    private static void replaceActiveProjectors(Collection<BlockPos> projectorPositions) {
        activeProjectorPositions.clear();
        activeProjectorPos = null;
        PROJECTOR_VISIBILITY_CACHE.clear();
        anchorInitialized = false;
        if (projectorPositions == null) {
            return;
        }
        for (BlockPos pos : projectorPositions) {
            if (pos != null) {
                activeProjectorPositions.add(pos.immutable());
            }
        }
    }

    private static void decodeLoop(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, long startOffsetMillis, long totalMillis, long generation,
            boolean catchUpDropsEnabled, boolean forceRgbaOutput) {
        if (CPU_BARS) {
            decodeCpuBarsLoop(targetWidth, targetHeight, fps);
            return;
        }
        long frameIntervalNs = fps > 0 ? Math.max(1L, 1_000_000_000L / fps) : 50_000_000L;
        long frameIndex = 0L;
        int consecutiveBadFrames = 0;
        try (AutoCloseable decoder = openDecoder(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative,
                decoderOverride, startOffsetMillis, totalMillis, forceRgbaOutput)) {
            activeDecoder = decoder;
            warnNativeOffsetLimitation(decoder, startOffsetMillis);
            while (running && generation == decodeGeneration.get()) {
                if (isGamePaused()) {
                    waitWhilePaused(generation);
                    continue;
                }
                if (activeRequiresProjector && !isActiveProjectorValid()) {
                    break;
                }
                DecodedFrame frame = nextDecodedFrame(decoder);
                if (frame == null) {
                    break;
                }
                frameIndex++;

                int dropped = 0;
                long nowNs = System.nanoTime();
                while (catchUpDropsEnabled && running && generation == decodeGeneration.get()
                        && nowNs - expectedFrameTimeNs(frameIndex, frameIntervalNs) > frameIntervalNs * 2L
                        && dropped < MAX_CATCH_UP_DROPS_PER_TICK) {
                    DecodedFrame catchUpDecoded = nextDecodedFrame(decoder);
                    if (catchUpDecoded == null) {
                        frame.close();
                        frame = null;
                        break;
                    }
                    frame.close();
                    frame = catchUpDecoded;
                    frameIndex++;
                    dropped++;
                    nowNs = System.nanoTime();
                }
                if (frame == null) {
                    break;
                }
                if (dropped > 0) {
                    LOGGER.debug("视频播放落后音频时间线，丢弃 {} 帧追赶 (frameIndex={}, lag={}ms)", dropped, frameIndex,
                            Math.max(0L, (System.nanoTime() - expectedFrameTimeNs(frameIndex, frameIntervalNs))
                                    / 1_000_000L));
                }

                long waitNs = expectedFrameTimeNs(frameIndex, frameIntervalNs) - System.nanoTime();
                if (waitNs > 0L) {
                    try {
                        java.util.concurrent.TimeUnit.NANOSECONDS.sleep(waitNs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                long uploadNs;
                try {
                    uploadNs = uploadFrameSync(frame, targetWidth, targetHeight, generation);
                    if (uploadNs < 0L) {
                        LOGGER.warn("视频 billboard 预览上传失败或客户端世界已退出");
                        break;
                    }
                } finally {
                    frame.close();
                }
                if (uploadNs > ADAPTIVE_FRAME_BUDGET_NS) {
                    consecutiveBadFrames++;
                    if (consecutiveBadFrames >= ADAPTIVE_BAD_FRAME_THRESHOLD
                            && requestAdaptiveDownscale(targetWidth, targetHeight, generation)) {
                        break;
                    }
                } else {
                    consecutiveBadFrames = Math.max(0, consecutiveBadFrames - 2);
                }

            }
        } catch (IOException e) {
            LOGGER.error("视频 billboard 预览解码失败", e);
        } catch (Exception e) {
            LOGGER.error("视频 billboard 预览 native 解码失败", e);
        } finally {
            if (generation == decodeGeneration.get()) {
                activeDecoder = null;
                running = false;
                started.set(false);
                decodeThread = null;
            }
        }
    }

    private static long expectedFrameTimeNs(long frameIndex, long frameIntervalNs) {
        long startNs = activeStartNanoTime;
        return startNs > 0L ? startNs + frameIndex * frameIntervalNs : System.nanoTime();
    }

    private static boolean isGamePaused() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.isPaused();
    }

    private static void waitWhilePaused(long generation) {
        long pauseStartNs = System.nanoTime();
        while (running && generation == decodeGeneration.get() && isGamePaused()) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        activeStartNanoTime += Math.max(0L, System.nanoTime() - pauseStartNs);
    }

    private static boolean isActiveProjectorValid() {
        Minecraft minecraft = Minecraft.getInstance();
        BlockPos projectorPos = activeProjectorPos;
        if (!activeRequiresProjector) {
            return true;
        }
        if (minecraft.level == null) {
            return false;
        }
        if (projectorPos != null && minecraft.level.getBlockEntity(projectorPos) instanceof VideoProjectorBlockEntity) {
            return true;
        }
        for (BlockPos pos : activeProjectorPositions) {
            if (minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity) {
                activeProjectorPos = pos;
                return true;
            }
        }
        return false;
    }

    private static void closeActiveDecoderAsync() {
        AutoCloseable decoder = activeDecoder;
        activeDecoder = null;
        if (decoder == null) {
            return;
        }
        Thread closer = new Thread(() -> {
            try {
                decoder.close();
            } catch (Exception ignored) {
            }
        }, "bili-video-decoder-close");
        closer.setDaemon(true);
        closer.start();
    }

    static AutoCloseable openDecoder(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, long startOffsetMillis, long totalMillis) throws IOException {
        return openDecoder(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride,
                startOffsetMillis, totalMillis, false);
    }

    static AutoCloseable openDecoder(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, long startOffsetMillis, long totalMillis,
            boolean forceRgbaOutput) throws IOException {
        if (preferNative) {
            IOException last = null;
            for (String hwaccel : VideoFeatureFlags.requestedHwaccelCandidates()) {
                try {
                    return new Fmp4NativeVideoDecoder(videoUrl, codecId, targetWidth, targetHeight,
                            Integer.MAX_VALUE, true,
                            forceRgbaOutput ? Fmp4NativeVideoDecoder.OutputFormat.RGBA : yuvDecodeFormat(), hwaccel,
                            startOffsetMillis, totalMillis, fps);
                } catch (IOException e) {
                    last = e;
                    LOGGER.warn("Native 视频解码器启动失败 hwaccel={}，尝试下一个候选: {}", hwaccel, e.toString());
                }
            }
            throw last != null ? last : new IOException("Native video decoder unavailable");
        }
        throw new IOException("视频投影仪不允许使用系统 ffmpeg；请启用/修复内置 native 解码器");
    }

    private static boolean requestAdaptiveDownscale(int currentWidth, int currentHeight, long generation) {
        PlaybackRequest req = activeRequest;
        if (req == null || generation != decodeGeneration.get() || currentWidth <= MIN_ADAPTIVE_WIDTH) {
            return false;
        }
        int nextWidth = Math.max(MIN_ADAPTIVE_WIDTH, Math.round(currentWidth * 0.75F));
        int nextHeight = Math.max(1, Math.round(currentHeight * (nextWidth / (float) currentWidth)));
        long elapsed = req.startOffsetMillis() + Math.max(0L, (System.nanoTime() - req.startedNanoTime()) / 1_000_000L);
        LOGGER.warn("视频上传持续超预算，优先保证游戏流畅：{}x{} -> {}x{}，允许丢帧并重启较低分辨率", currentWidth,
                currentHeight, nextWidth, nextHeight);
        Minecraft.getInstance().execute(() -> {
            stopForReplace();
            startInternal(req.videoUrl(), nextWidth, nextHeight, req.fps(), req.codecId(), req.preferNative(),
                    req.decoderOverride(), req.sessionId(), elapsed, req.totalMillis(), req.anchorPositions(), true,
                    req.forceRgbaOutput());
        });
        return true;
    }

    private static void warnNativeOffsetLimitation(AutoCloseable decoder, long startOffsetMillis) {
        if (decoder instanceof Fmp4NativeVideoDecoder && startOffsetMillis > 0L) {
            LOGGER.debug("Native 视频投影使用内置 fMP4 Range seek 起播: offset={}ms", startOffsetMillis);
        }
    }

    static byte[] nextFrame(AutoCloseable decoder) throws Exception {
        DecodedFrame frame = nextDecodedFrame(decoder);
        return frame != null ? frame.rgba() : null;
    }

    static DecodedFrame nextDecodedFrame(AutoCloseable decoder) throws Exception {
        if (decoder instanceof Fmp4NativeVideoDecoder nativeDecoder) {
            return DecodedFrame.wrap(nativeDecoder.getNextDecodedFrame());
        }
        throw new IOException("unsupported video decoder: " + decoder.getClass().getName());
    }

    static final class DecodedFrame implements AutoCloseable {
        private final Fmp4NativeVideoDecoder.DecodedFrame.Format format;
        private final byte[] data;
        private final ByteBuffer buffer;
        private final int byteLength;
        private final AutoCloseable delegate;
        private final long ptsNanos;

        private DecodedFrame(byte[] rgba, AutoCloseable delegate) {
            this(Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, rgba, null,
                    rgba != null ? rgba.length : 0, delegate, -1L);
        }

        private DecodedFrame(byte[] rgba, AutoCloseable delegate, long ptsNanos) {
            this(Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, rgba, null,
                    rgba != null ? rgba.length : 0, delegate, ptsNanos);
        }

        private DecodedFrame(Fmp4NativeVideoDecoder.DecodedFrame.Format format, byte[] data, AutoCloseable delegate,
                long ptsNanos) {
            this(format, data, null, data != null ? data.length : 0, delegate, ptsNanos);
        }

        private DecodedFrame(Fmp4NativeVideoDecoder.DecodedFrame.Format format, byte[] data, ByteBuffer buffer,
                int byteLength, AutoCloseable delegate, long ptsNanos) {
            this.format = format != null ? format : Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA;
            this.data = data;
            this.buffer = buffer;
            this.byteLength = Math.max(0, byteLength);
            this.delegate = delegate;
            this.ptsNanos = ptsNanos;
        }

        static DecodedFrame wrap(byte[] rgba) {
            return rgba != null ? new DecodedFrame(rgba, null) : null;
        }

        static DecodedFrame wrap(Fmp4NativeVideoDecoder.DecodedFrame frame) {
            if (frame == null) {
                return null;
            }
            ByteBuffer buffer = frame.buffer();
            return buffer != null
                    ? new DecodedFrame(frame.format(), null, buffer, frame.byteLength(), frame, frame.ptsNanos())
                    : new DecodedFrame(frame.format(), frame.data(), frame, frame.ptsNanos());
        }

        Fmp4NativeVideoDecoder.DecodedFrame.Format format() {
            return format;
        }

        byte[] data() {
            if (data == null && buffer != null) {
                ByteBuffer src = buffer();
                byte[] copy = new byte[src.remaining()];
                src.get(copy);
                return copy;
            }
            return data;
        }

        ByteBuffer buffer() {
            if (buffer == null) {
                return null;
            }
            ByteBuffer duplicate = buffer.duplicate();
            duplicate.position(0);
            duplicate.limit(Math.min(duplicate.capacity(), byteLength()));
            return duplicate.slice().order(buffer.order());
        }

        int byteLength() {
            if (byteLength > 0) {
                return byteLength;
            }
            return data != null ? data.length : 0;
        }

        byte[] rgba() {
            if (format != Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA) {
                throw new IllegalStateException("decoded frame is " + format + ", not RGBA");
            }
            return data;
        }

        long ptsNanos() {
            return ptsNanos;
        }

        @Override
        public void close() {
            if (delegate != null) {
                try {
                    delegate.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private record PlaybackRequest(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, String sessionId, long startOffsetMillis,
            long totalMillis, List<BlockPos> anchorPositions, long startedNanoTime, boolean forceRgbaOutput) {
        PlaybackRequest(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
                boolean preferNative, String decoderOverride, String sessionId, long startOffsetMillis,
                long totalMillis, Collection<BlockPos> anchorPositions, boolean forceRgbaOutput) {
            this(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, sessionId,
                    startOffsetMillis, totalMillis, immutablePositions(anchorPositions), System.nanoTime(),
                    forceRgbaOutput);
        }
    }

    static List<BlockPos> immutablePositions(Collection<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        return positions.stream()
                .filter(pos -> pos != null)
                .map(pos -> pos.immutable())
                .toList();
    }

    public record VideoStatus(int width, int height, int fps, boolean hasFrame, boolean synced) {
        static VideoStatus empty() {
            return new VideoStatus(0, 0, 0, false, false);
        }

        public boolean active() {
            return width > 0 && height > 0 && fps > 0;
        }
    }

    public record VideoSyncStatus(boolean running, boolean hasFrame, long mediaMillis, long queuedMediaMillis,
            int width, int height, int fps) {
        static VideoSyncStatus empty() {
            return new VideoSyncStatus(false, false, -1L, -1L, 0, 0, 0);
        }
    }

    private static void decodeTestPatternLoop(int targetWidth, int targetHeight, int fps) {
        if (CPU_BARS) {
            decodeCpuBarsLoop(targetWidth, targetHeight, fps);
            return;
        }
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-v", "error",
                    "-nostdin",
                    "-f", "lavfi",
                    "-i", "testsrc2=size=" + targetWidth + "x" + targetHeight + ":rate=" + fps,
                    "-pix_fmt", "rgba",
                    "-f", "rawvideo",
                    "-an",
                    "-");
            pb.redirectError(ProcessBuilder.Redirect.PIPE);
            process = pb.start();
            Process ffmpegProcess = process;
            Thread stderrReader = new Thread(() -> logProcessStderr(ffmpegProcess), "ffmpeg-test-pattern-stderr");
            stderrReader.setDaemon(true);
            stderrReader.start();

            readRawVideoLoop(process.getInputStream(), targetWidth, targetHeight, fps);
        } catch (IOException e) {
            LOGGER.error("视频 billboard 本地测试图启动失败，请确认系统 ffmpeg 在 PATH 中", e);
        } finally {
            if (process != null) {
                process.destroy();
            }
            running = false;
            started.set(false);
        }
    }

    private static void decodeCpuBarsLoop(int targetWidth, int targetHeight, int fps) {
        int evenWidth = Math.max(2, targetWidth & ~1);
        int evenHeight = Math.max(2, targetHeight & ~1);
        int frameSize = CUSTOM_YUV_SHADER_BACKEND ? evenWidth * evenHeight * 3 / 2 : targetWidth * targetHeight * 4;
        long frameDelayMs = fps > 0 ? Math.max(1L, 1000L / fps) : 50L;
        long frameIndex = 0L;
        LOGGER.info("视频 billboard 使用 CPU 纯色彩条诊断模式: {}x{} @ {}fps, yuvShaderBackend={}",
                CUSTOM_YUV_SHADER_BACKEND ? evenWidth : targetWidth,
                CUSTOM_YUV_SHADER_BACKEND ? evenHeight : targetHeight,
                fps, CUSTOM_YUV_SHADER_BACKEND);
        try {
            while (running) {
                byte[] frame = new byte[frameSize];
                long currentFrameIndex = frameIndex++;
                if (CUSTOM_YUV_SHADER_BACKEND) {
                    fillCpuBarsYuv420p(frame, evenWidth, evenHeight, currentFrameIndex);
                    Minecraft.getInstance()
                            .execute(() -> uploadYuv420FrameOnRenderThreadForBench(frame, evenWidth, evenHeight));
                } else {
                    fillCpuBars(frame, targetWidth, targetHeight, currentFrameIndex);
                    Minecraft.getInstance().execute(() -> uploadFrame(frame, targetWidth, targetHeight));
                }
                try {
                    Thread.sleep(frameDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            running = false;
            started.set(false);
        }
    }

    private static void fillCpuBarsYuv420p(byte[] frame, int frameWidth, int frameHeight, long frameIndex) {
        int ySize = frameWidth * frameHeight;
        int uvWidth = frameWidth / 2;
        int uvHeight = frameHeight / 2;
        int uBase = ySize;
        int vBase = ySize + uvWidth * uvHeight;
        int phase = (int) (frameIndex % Math.max(1, frameWidth));
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                int[] rgb = cpuBarRgb(x, frameWidth, phase);
                int[] yuv = rgbToLimitedBt709(rgb[0], rgb[1], rgb[2]);
                frame[y * frameWidth + x] = (byte) yuv[0];
            }
        }
        for (int y = 0; y < uvHeight; y++) {
            for (int x = 0; x < uvWidth; x++) {
                int sx = Math.min(frameWidth - 1, x * 2);
                int[] rgb = cpuBarRgb(sx, frameWidth, phase);
                int[] yuv = rgbToLimitedBt709(rgb[0], rgb[1], rgb[2]);
                int i = y * uvWidth + x;
                frame[uBase + i] = (byte) yuv[1];
                frame[vBase + i] = (byte) yuv[2];
            }
        }
    }

    private static void fillCpuBars(byte[] frame, int frameWidth, int frameHeight, long frameIndex) {
        int phase = (int) (frameIndex % Math.max(1, frameWidth));
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                int[] rgb = cpuBarRgb(x, frameWidth, phase);
                int i = (y * frameWidth + x) * 4;
                frame[i] = (byte) rgb[0];
                frame[i + 1] = (byte) rgb[1];
                frame[i + 2] = (byte) rgb[2];
                frame[i + 3] = (byte) 255;
            }
        }
    }

    private static int[] cpuBarRgb(int x, int frameWidth, int phase) {
        int r;
        int g;
        int b;
        switch ((x * 8) / Math.max(1, frameWidth)) {
            case 0 -> {
                r = 255;
                g = 0;
                b = 0;
            }
            case 1 -> {
                r = 0;
                g = 255;
                b = 0;
            }
            case 2 -> {
                r = 0;
                g = 0;
                b = 255;
            }
            case 3 -> {
                r = 255;
                g = 255;
                b = 0;
            }
            case 4 -> {
                r = 0;
                g = 255;
                b = 255;
            }
            case 5 -> {
                r = 255;
                g = 0;
                b = 255;
            }
            case 6 -> {
                r = 255;
                g = 255;
                b = 255;
            }
            default -> {
                r = 32;
                g = 32;
                b = 32;
            }
        }
        if (Math.abs(((x + phase) % frameWidth) - frameWidth / 2) < 6) {
            r = 255;
            g = 255;
            b = 255;
        }
        return new int[] { r, g, b };
    }

    private static int[] rgbToLimitedBt709(int r, int g, int b) {
        int y = Math.round(16.0F + (65.481F * r + 128.553F * g + 24.966F * b) / 255.0F);
        int u = Math.round(128.0F + (-37.797F * r - 74.203F * g + 112.0F * b) / 255.0F);
        int v = Math.round(128.0F + (112.0F * r - 93.786F * g - 18.214F * b) / 255.0F);
        return new int[] { clampByte(y), clampByte(u), clampByte(v) };
    }

    private static int clampByte(int value) {
        return value < 0 ? 0 : value > 255 ? 255 : value;
    }

    private static void readRawVideoLoop(InputStream stdout, int targetWidth, int targetHeight, int fps)
            throws IOException {
        int frameSize = targetWidth * targetHeight * 4;
        long frameDelayMs = fps > 0 ? Math.max(1L, 1000L / fps) : 50L;
        while (running) {
            long startNs = System.nanoTime();
            byte[] frame = new byte[frameSize];
            int totalRead = 0;
            while (totalRead < frameSize) {
                int n = stdout.read(frame, totalRead, frameSize - totalRead);
                if (n < 0) {
                    return;
                }
                totalRead += n;
            }
            Minecraft.getInstance().execute(() -> uploadFrame(frame, targetWidth, targetHeight));

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            long sleepMs = frameDelayMs - elapsedMs;
            if (sleepMs > 0L) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void logProcessStderr(Process process) {
        try (InputStream in = process.getErrorStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    String line = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (!line.isBlank()) {
                        LOGGER.error("ffmpeg-test-pattern: {}", line);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void uploadFrame(byte[] rgba, int frameWidth, int frameHeight) {
        uploadFrameOnRenderThread(rgba, frameWidth, frameHeight);
    }

    private static long uploadFrameSync(DecodedFrame frame, int frameWidth, int frameHeight, long generation) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || generation != decodeGeneration.get() || !running || !isActiveProjectorValid()) {
            return -1L;
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            if (generation != decodeGeneration.get() || !running || !isActiveProjectorValid()) {
                future.complete(-1L);
                return;
            }
            long startNs = System.nanoTime();
            boolean ok = uploadDecodedFrameOnRenderThread(frame, frameWidth, frameHeight);
            future.complete(ok ? System.nanoTime() - startNs : -1L);
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException e) {
            LOGGER.error("视频渲染上传帧失败", e);
            return -1L;
        }
    }

    private static boolean uploadDecodedFrameOnRenderThread(DecodedFrame frame, int frameWidth, int frameHeight) {
        if (isCustomYuvShaderAvailable() && frame != null && isYuvFrameFormat(frame.format())) {
            return uploadYuvFrameOnRenderThread(frame, frameWidth, frameHeight);
        }
        return uploadFrameOnRenderThread(Yuv420pConverter.toUploadRgba(frame, frameWidth, frameHeight), frameWidth,
                frameHeight);
    }

    public static long uploadFrameSyncForBench(byte[] rgba, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return -1L;
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            long startNs = System.nanoTime();
            boolean ok = uploadFrameOnRenderThread(rgba, frameWidth, frameHeight);
            future.complete(ok ? System.nanoTime() - startNs : -1L);
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException e) {
            LOGGER.error("视频渲染 bench 上传帧失败", e);
            return -1L;
        }
    }

    public static long uploadDecodedFrameSyncForBench(Fmp4NativeVideoDecoder.DecodedFrame frame, int frameWidth,
            int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || frame == null) {
            return -1L;
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            long startNs = System.nanoTime();
            boolean ok = uploadDecodedFrameOnRenderThread(DecodedFrame.wrap(frame), frameWidth, frameHeight);
            future.complete(ok ? System.nanoTime() - startNs : -1L);
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException e) {
            LOGGER.error("视频渲染 bench decoded frame 上传失败", e);
            return -1L;
        }
    }

    public static long uploadPackedBytesSyncForBench(byte[] packedRgbaBytes, int textureWidth, int textureHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return -1L;
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            long startNs = System.nanoTime();
            boolean ok = uploadPackedBytesOnRenderThread(packedRgbaBytes, textureWidth, textureHeight);
            future.complete(ok ? System.nanoTime() - startNs : -1L);
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException e) {
            LOGGER.error("视频渲染 bench packed 上传失败", e);
            return -1L;
        }
    }

    public static long uploadYuv420FrameSyncForBench(byte[] yuv420p, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return -1L;
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            long startNs = System.nanoTime();
            boolean ok = uploadYuv420FrameOnRenderThreadForBench(yuv420p, frameWidth, frameHeight);
            future.complete(ok ? System.nanoTime() - startNs : -1L);
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException e) {
            LOGGER.error("视频渲染 bench YUV420P 三平面上传失败", e);
            return -1L;
        }
    }

    public static long uploadNv12FrameSyncForBench(byte[] nv12, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return -1L;
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            long startNs = System.nanoTime();
            boolean ok = uploadNv12FrameOnRenderThreadForBench(nv12, frameWidth, frameHeight);
            future.complete(ok ? System.nanoTime() - startNs : -1L);
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException e) {
            LOGGER.error("视频渲染 bench NV12 双平面上传失败", e);
            return -1L;
        }
    }

    private static boolean uploadYuv420FrameOnRenderThreadForBench(byte[] yuv420p, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        if (!YUV_UPLOAD_PLANES) {
            LOGGER.warn("YUV420P bench 诊断：ncpb.video.yuv.upload_planes=false，跳过 RED8 三平面纹理创建，临时 CPU 转 RGBA 上传");
            return uploadFrameOnRenderThread(Yuv420pConverter.yuv420pToRgba(yuv420p, frameWidth, frameHeight),
                    frameWidth, frameHeight);
        }
        if (!isCustomYuvShaderAvailable()) {
            return uploadFrameOnRenderThread(Yuv420pConverter.yuv420pToRgba(yuv420p, frameWidth, frameHeight),
                    frameWidth, frameHeight);
        }
        // real_bench 是“真实播放链路压测”，不是纯上传微基准；复用 preview 的 YUV 纹理集，
        // 这样 SubmitCustomGeometry 能把正在压测的帧真正画到世界里。独立 bench 纹理仅保留给未来纯上传对照。
        ensureYuvTextureSet(Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P);
        boolean ok = yuvTextureSet instanceof Yuv420pTextureSet yuv420pTextures
                && yuv420pTextures.upload(yuv420p, frameWidth, frameHeight);
        if (!ok) {
            LOGGER.warn("视频 YUV420P bench 帧大小不足: bytes={}, expected={}",
                    yuv420p != null ? yuv420p.length : 0, frameWidth * frameHeight * 3 / 2);
            return false;
        }
        width = frameWidth;
        height = frameHeight;
        hasFrame = true;
        return true;
    }

    private static boolean uploadNv12FrameOnRenderThreadForBench(byte[] nv12, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        if (!isCustomYuvShaderAvailable()) {
            return uploadFrameOnRenderThread(Yuv420pConverter.nv12ToRgba(nv12, frameWidth, frameHeight), frameWidth,
                    frameHeight);
        }
        ensureYuvTextureSet(Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12);
        boolean ok = yuvTextureSet instanceof Nv12TextureSet nv12Textures
                && nv12Textures.upload(nv12, frameWidth, frameHeight);
        if (!ok) {
            LOGGER.warn("视频 NV12 bench 帧大小不足: bytes={}, expected={}",
                    nv12 != null ? nv12.length : 0, frameWidth * frameHeight * 3 / 2);
            return false;
        }
        width = frameWidth;
        height = frameHeight;
        hasFrame = true;
        return true;
    }

    private static boolean uploadPackedBytesOnRenderThread(byte[] packedRgbaBytes, int textureWidth,
            int textureHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        int byteCount = textureWidth * textureHeight * 4;
        if (packedRgbaBytes.length < byteCount) {
            LOGGER.warn("视频 packed 帧大小不足: {} < {}", packedRgbaBytes.length, byteCount);
            return false;
        }

        ensurePackedBenchTexture(textureWidth, textureHeight);
        NativeImage image = packedBenchTexture.getPixels();
        if (image == null || image.isClosed()) {
            return false;
        }

        VideoFrameUploader.uploadPackedRgbaBytes(image, packedRgbaBytes, textureWidth, textureHeight);
        packedBenchTexture.upload();
        return true;
    }

    private static boolean uploadFrameOnRenderThread(byte[] rgba, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        if (rgba.length < frameWidth * frameHeight * 4) {
            LOGGER.warn("视频帧大小不足: {} < {}", rgba.length, frameWidth * frameHeight * 4);
            return false;
        }

        ensureTexture(frameWidth, frameHeight);
        NativeImage image = texture.getPixels();
        if (image == null || image.isClosed()) {
            return false;
        }

        VideoFrameUploader.uploadRgba(image, rgba, frameWidth, frameHeight);
        texture.upload();
        hasFrame = true;
        return true;
    }

    private static boolean uploadYuvFrameOnRenderThread(DecodedFrame frame, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        if (!YUV_UPLOAD_PLANES) {
            LOGGER.warn("YUV preview 诊断：ncpb.video.yuv.upload_planes=false，跳过多平面纹理创建，临时 CPU 转 RGBA 上传");
            return uploadFrameOnRenderThread(Yuv420pConverter.toUploadRgba(frame, frameWidth, frameHeight), frameWidth,
                    frameHeight);
        }
        ensureYuvTextureSet(frame.format());
        if (!uploadYuvFrameData(yuvTextureSet, frame, frameWidth, frameHeight)) {
            LOGGER.warn("YUV 视频帧大小不足或格式错误: format={}, bytes={}", frame != null ? frame.format() : null,
                    frame != null ? frame.byteLength() : 0);
            return false;
        }
        width = frameWidth;
        height = frameHeight;
        hasFrame = true;
        return true;
    }

    private static boolean uploadYuvFrameData(VideoYuvTextureSet textureSet, DecodedFrame frame, int width,
            int height) {
        if (textureSet == null || frame == null || textureSet.format() != frame.format()) {
            return false;
        }
        java.nio.ByteBuffer buffer = frame.buffer();
        if (buffer != null) {
            return textureSet.upload(buffer, frame.byteLength(), width, height);
        }
        return textureSet.upload(frame.data(), width, height);
    }

    private static void ensureYuvTextureSet(Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
        Fmp4NativeVideoDecoder.DecodedFrame.Format normalized = format == Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                ? Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                : Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
        if (yuvTextureSet != null && yuvTextureSet.format() == normalized) {
            return;
        }
        if (yuvTextureSet != null) {
            yuvTextureSet.close();
        }
        if (normalized == Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P) {
            yuvTextureSet = new Yuv420pTextureSet(YUV_TEXTURE_Y_ID, YUV_TEXTURE_U_ID, YUV_TEXTURE_V_ID,
                    "bili_video_billboard_preview_yuv420p");
        } else {
            yuvTextureSet = new Nv12TextureSet(YUV_TEXTURE_Y_ID, YUV_TEXTURE_U_ID, YUV_TEXTURE_Y_ID,
                    "bili_video_billboard_preview_nv12");
        }
    }

    private static void ensureTexture(int frameWidth, int frameHeight) {
        if (texture != null) {
            NativeImage image = texture.getPixels();
            if (image != null && !image.isClosed()
                    && image.getWidth() == frameWidth && image.getHeight() == frameHeight) {
                width = frameWidth;
                height = frameHeight;
                return;
            }
        }
        releaseTexture();
        width = frameWidth;
        height = frameHeight;
        texture = new DynamicTexture("bili_video_billboard_preview", frameWidth, frameHeight, false);
        Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
    }

    private static void ensurePackedBenchTexture(int textureWidth, int textureHeight) {
        if (packedBenchTexture != null) {
            NativeImage image = packedBenchTexture.getPixels();
            if (image != null && !image.isClosed()
                    && image.getWidth() == textureWidth && image.getHeight() == textureHeight) {
                return;
            }
        }
        releasePackedBenchTexture();
        packedBenchTexture = new DynamicTexture("bili_video_packed_bench", textureWidth, textureHeight, false);
        Minecraft.getInstance().getTextureManager().register(PACKED_BENCH_TEXTURE_ID, packedBenchTexture);
        LOGGER.info("视频 packed bench 动态纹理已创建: {} ({}x{}), fastNativeUpload={}; 仅用于上传计时，不参与 billboard 渲染",
                PACKED_BENCH_TEXTURE_ID, textureWidth, textureHeight,
                VideoFrameUploader.fastNativeUploadAvailable());
    }

    private static void releaseTexture() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE_ID);
            texture.close();
            texture = null;
        }
        if (yuvTextureSet != null) {
            yuvTextureSet.close();
            yuvTextureSet = null;
        }
        releasePackedBenchTexture();
    }

    private static void releasePackedBenchTexture() {
        if (packedBenchTexture != null) {
            Minecraft.getInstance().getTextureManager().release(PACKED_BENCH_TEXTURE_ID);
            packedBenchTexture.close();
            packedBenchTexture = null;
        }
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || INSTANCES.isEmpty()) {
            return;
        }
        observeCameraContinuity(minecraft);
        for (VideoPlaybackInstance instance : INSTANCES.values()) {
            instance.pumpUploadOnRenderThread();
        }
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            for (VideoPlaybackInstance instance : INSTANCES.values()) {
                instance.stop();
            }
            INSTANCES.clear();
            running = false;
            hasFrame = false;
            return;
        }
        if (!INSTANCES.isEmpty()) {
            Camera camera = minecraft.gameRenderer.getMainCamera();
            INSTANCES.entrySet().removeIf(entry -> {
                VideoPlaybackInstance instance = entry.getValue();
                if (!instance.hasVideoConsumer()) {
                    instance.stop();
                    return true;
                }
                if (!instance.hasGuiConsumer() && !instance.isWithinAudioRange(minecraft)) {
                    instance.stop();
                    return true;
                }
                instance.submit(event, minecraft, camera);
                return !instance.isRunning() && !instance.hasFrame();
            });
        }
        boolean renderYuvFrame = shouldRenderYuvFrame();
        if (!hasFrame || (!renderYuvFrame && texture == null) || width <= 0 || height <= 0) {
            return;
        }

        // Iris 会捕获 SubmitCustomGeometry；YUV 兼容模式改走 immediate，避免留下不可见深度状态。
        // 加载占位层仍由 VideoPlaybackInstance.submit(...) 单独提交。
        if (renderYuvFrame && shouldDrawYuvImmediateWithIris()) {
            return;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (activeRequiresProjector) {
            List<VideoProjectorBlockEntity> projectors = activeVideoProjectors(minecraft);
            if (projectors.isEmpty()) {
                stop();
                return;
            }
            for (VideoProjectorBlockEntity projector : projectors) {
                if (HolographicGlassesClient.shouldHideProjectorVideos()) {
                    submitProjectorPrivacyOverlay(event, minecraft, camera, projector);
                } else if (renderYuvFrame) {
                    submitProjectorYuvGeometry(event, minecraft, camera, projector, yuvTextureSet);
                } else {
                    submitProjectorGeometry(event, minecraft, camera, projector);
                }
            }
            return;
        }

        VideoProjectorBlockEntity projector = activeVideoProjector(minecraft);
        if (activeRequiresProjector && projector == null) {
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

        if (WORLD_ANCHORED || activeRequiresProjector) {
            if (!ensureWorldAnchor(minecraft, camera, projector)) {
                return;
            }
            Vec3 cameraPos = camera.position();
            double dx = anchorX - cameraPos.x;
            double dy = anchorY - cameraPos.y;
            double dz = anchorZ - cameraPos.z;
            if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQR) {
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
                        ? yuvRenderTypeForCurrentIrisProgram(yuvTextureSet)
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
        if (!shouldDrawYuvImmediateWithIris() || INSTANCES.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        for (VideoPlaybackInstance instance : INSTANCES.values()) {
            instance.renderYuvImmediate(event, route);
        }
    }

    private static void renderPreviewYuvImmediate(RenderLevelStageEvent event, String route) {
        if (loggedYuvImmediateStage.compareAndSet(false, true)) {
            LOGGER.warn("Iris/YUV: 当前非投影预览 immediate 绘制阶段为 '{}'，坐标模式为 '{}'，pose='{}'，route={}",
                    YUV_IMMEDIATE_STAGE, YUV_IMMEDIATE_COORDS, YUV_IMMEDIATE_POSE, route);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || activeRequiresProjector) {
            return;
        }
        if (!hasFrame || !shouldRenderYuvFrame() || yuvTextureSet == null || width <= 0 || height <= 0) {
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

        RenderType renderType = yuvRenderTypeForCurrentIrisProgram(yuvTextureSet);
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
        return IrisShaderpackCompat.shouldApplyIrisYuvCompatibility();
    }

    private static void renderProjectorYuvImmediate(RenderLevelStageEvent event, String route) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !activeRequiresProjector) {
            return;
        }
        if (!hasFrame || !shouldRenderYuvFrame() || yuvTextureSet == null || width <= 0 || height <= 0) {
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
            LOGGER.warn("Iris/YUV: shaderpack 下投影仪 YUV 改用 immediate 绘制阶段 '{}'，坐标模式 '{}'，route={}",
                    YUV_IMMEDIATE_STAGE, YUV_IMMEDIATE_COORDS, route);
        }

        for (VideoProjectorBlockEntity projector : projectors) {
            if (HolographicGlassesClient.shouldHideProjectorVideos()) {
                drawProjectorPrivacyOverlayImmediate(event, minecraft, camera, projector, route);
            } else {
                drawProjectorYuvImmediate(event, minecraft, camera, projector, yuvTextureSet, route, cameraRelative);
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

    private static boolean drawProjectorYuvImmediate(RenderLevelStageEvent event, Minecraft minecraft, Camera camera,
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

    private static void drawWithEventModelView(RenderType renderType, MeshData mesh, RenderLevelStageEvent event) {
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        try {
            // RenderType.draw() 使用当前模型视图矩阵；这里切到事件提供的世界矩阵，避免相机相对顶点漂移。
            modelViewStack.set(event.getModelViewMatrix());
            renderType.draw(mesh);
        } finally {
            modelViewStack.popMatrix();
        }
    }

    private static void logFirstImmediateQuad(PreviewQuad quad, Camera camera, boolean cameraRelative,
            boolean forceWorldAnchored) {
        if (firstImmediateQuadLogged) {
            return;
        }
        firstImmediateQuadLogged = true;
        Vec3 cameraPos = camera.position();
        LOGGER.debug(
                "Iris/YUV immediate quad: cameraRelative={}, forceWorldAnchored={}, pose='{}', anchor=({}, {}, {}), "
                        + "anchorYaw={}, camera=({}, {}, {}), p0=({}, {}, {}), p1=({}, {}, {}), p2=({}, {}, {}), p3=({}, {}, {})",
                cameraRelative, forceWorldAnchored, YUV_IMMEDIATE_POSE,
                fmt(anchorX), fmt(anchorY), fmt(anchorZ), fmt(anchorYawDeg),
                fmt(cameraPos.x), fmt(cameraPos.y), fmt(cameraPos.z),
                fmt(quad.p0x()), fmt(quad.p0y()), fmt(quad.p0z()),
                fmt(quad.p1x()), fmt(quad.p1y()), fmt(quad.p1z()),
                fmt(quad.p2x()), fmt(quad.p2y()), fmt(quad.p2z()),
                fmt(quad.p3x()), fmt(quad.p3y()), fmt(quad.p3z()));
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static PreviewQuad computePreviewQuad(Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector,
            int textureWidth, int textureHeight, boolean cameraRelative, boolean forceWorldAnchored) {
        float scale = projector != null ? Math.abs(projector.getProjectionScale()) : 1.0F;
        float aspect = textureWidth / (float) textureHeight;
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

        if (forceWorldAnchored || WORLD_ANCHORED || activeRequiresProjector) {
            if (!ensureWorldAnchor(minecraft, camera, projector)) {
                return null;
            }
            Vec3 cameraPos = camera.position();
            double dx = anchorX - cameraPos.x;
            double dy = anchorY - cameraPos.y;
            double dz = anchorZ - cameraPos.z;
            if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQR) {
                return null;
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

            float cx = cameraRelative ? (float) (anchorX - cameraPos.x) : (float) anchorX;
            float cy = cameraRelative ? (float) (anchorY - cameraPos.y) : (float) anchorY;
            float cz = cameraRelative ? (float) (anchorZ - cameraPos.z) : (float) anchorZ;
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

        return new PreviewQuad(p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z);
    }

    private record PreviewQuad(float p0x, float p0y, float p0z, float p1x, float p1y, float p1z,
            float p2x, float p2y, float p2z, float p3x, float p3y, float p3z) {
    }

    private static boolean shouldRenderYuvFrame() {
        // 正常播放由 render.backend 选择 shader；real_bench 的 yuv420 上传会直接写入 yuvTextureSet，
        // 此时即使全局 backend 仍是 rgba，也应该把压测帧提交出来，否则日志在跑但世界里没有画面。
        return yuvTextureSet != null && isCustomYuvShaderAvailable() && (CUSTOM_YUV_SHADER_BACKEND || texture == null);
    }

    public static boolean isCustomYuvShaderAvailable() {
        return CUSTOM_YUV_SHADER_BACKEND && !IrisShaderpackCompat.shouldDisableCustomYuvShader();
    }

    static Fmp4NativeVideoDecoder.OutputFormat yuvDecodeFormat() {
        if (NV12_DECODE_BACKEND) {
            return Fmp4NativeVideoDecoder.OutputFormat.NV12;
        }
        if (YUV420_DECODE_BACKEND) {
            return Fmp4NativeVideoDecoder.OutputFormat.YUV420P;
        }
        return Fmp4NativeVideoDecoder.OutputFormat.RGBA;
    }

    private static boolean isYuvFrameFormat(Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
        return format == Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                || format == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
    }

    private static void submitProjectorGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector) {
        submitProjectorGeometry(event, minecraft, camera, projector, TEXTURE_ID, width, height);
    }

    static void submitProjectorGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, Identifier renderTextureId, int textureWidth, int textureHeight) {
        submitProjectorGeometry(event, minecraft, camera, projector, renderTextureId, textureWidth, textureHeight,
                0.0D, YuvVideoRenderTypes.videoRgbaEntity(renderTextureId), "projector-rgba");
    }

    static void submitProjectorEmissiveGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, Identifier renderTextureId, int textureWidth, int textureHeight) {
        submitProjectorGeometry(event, minecraft, camera, projector, renderTextureId, textureWidth, textureHeight,
                0.0D, RenderTypes.itemCutout(renderTextureId), "projector-rgba-placeholder");
    }

    static void submitProjectorPrivacyOverlay(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector) {
        PreviewQuad quad = computePreviewQuad(minecraft, camera, projector,
                320, 180, true, true);
        if (quad == null) {
            return;
        }
        PoseStack poseStack = new PoseStack();
        HolographicPrivacyOverlay.submit(event.getSubmitNodeCollector(), poseStack,
                quad.p0x(), quad.p0y(), quad.p0z() + 0.003F,
                quad.p1x(), quad.p1y(), quad.p1z() + 0.003F,
                quad.p2x(), quad.p2y(), quad.p2z() + 0.003F,
                quad.p3x(), quad.p3y(), quad.p3z() + 0.003F);
    }

    static boolean drawProjectorPrivacyOverlayImmediate(RenderLevelStageEvent event, Minecraft minecraft,
            Camera camera, VideoProjectorBlockEntity projector, String route) {
        PreviewQuad quad = computePreviewQuad(minecraft, camera, projector,
                320, 180, true, true);
        if (quad == null) {
            return false;
        }
        RenderType renderType = YuvVideoRenderTypes.videoRgbaEntity(HolographicPrivacyOverlay.textureId());
        BufferBuilder builder = Tesselator.getInstance().begin(renderType.mode(), renderType.format());
        PoseStack poseStack = "identity".equals(YUV_IMMEDIATE_POSE) ? new PoseStack() : event.getPoseStack();
        PoseStack.Pose pose = poseStack.last();
        emitQuad(builder, pose, quad.p0x(), quad.p0y(), quad.p0z() + 0.003F,
                quad.p1x(), quad.p1y(), quad.p1z() + 0.003F,
                quad.p2x(), quad.p2y(), quad.p2z() + 0.003F,
                quad.p3x(), quad.p3y(), quad.p3z() + 0.003F, false);
        emitQuad(builder, pose, quad.p0x(), quad.p0y(), quad.p0z() + 0.003F,
                quad.p1x(), quad.p1y(), quad.p1z() + 0.003F,
                quad.p2x(), quad.p2y(), quad.p2z() + 0.003F,
                quad.p3x(), quad.p3y(), quad.p3z() + 0.003F, true);
        MeshData mesh = builder.build();
        if (mesh == null) {
            return false;
        }
        drawWithEventModelView(renderType, mesh, event);
        return true;
    }

    static void submitProjectorViewDepthOffsetGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft,
            Camera camera, VideoProjectorBlockEntity projector, Identifier renderTextureId, int textureWidth,
            int textureHeight, double viewDepthOffset) {
        submitProjectorGeometry(event, minecraft, camera, projector, renderTextureId, textureWidth, textureHeight,
                Math.max(0.0D, viewDepthOffset), YuvVideoRenderTypes.videoRgbaEntity(renderTextureId),
                "projector-rgba-depth-offset");
    }

    private static void submitProjectorGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, Identifier renderTextureId, int textureWidth, int textureHeight,
            double viewDepthOffset, RenderType renderType, String route) {
        float scale = Math.abs(projector.getProjectionScale());
        float aspect = textureWidth / (float) textureHeight;
        float halfHeight = HEIGHT * scale * 0.5F;
        float halfWidth = halfHeight * aspect;

        if (!ensureWorldAnchor(minecraft, camera, projector)) {
            return;
        }
        Vec3 cameraPos = camera.position();
        double dx = anchorX - cameraPos.x;
        double dy = anchorY - cameraPos.y;
        double dz = anchorZ - cameraPos.z;
        if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQR) {
            return;
        }
        if (!isScreenInView(camera, anchorX, anchorY, anchorZ)) {
            return;
        }

        double yawRad = Math.toRadians(anchorYawDeg);
        double pitchRad = Math.toRadians(projector.getProjectionPitch());
        float rightX = (float) Math.cos(yawRad);
        float rightZ = (float) Math.sin(yawRad);
        float forwardX = (float) -Math.sin(yawRad);
        float forwardZ = (float) Math.cos(yawRad);
        float upX = (float) (forwardX * Math.sin(pitchRad));
        float upY = (float) Math.cos(pitchRad);
        float upZ = (float) (forwardZ * Math.sin(pitchRad));

        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double depthOffsetScale = viewDepthOffset > 0.0D && distance > 1.0e-4D ? viewDepthOffset / distance : 0.0D;
        float cx = (float) (anchorX - cameraPos.x + dx * depthOffsetScale);
        float cy = (float) (anchorY - cameraPos.y + dy * depthOffsetScale);
        float cz = (float) (anchorZ - cameraPos.z + dz * depthOffsetScale);
        float rx = rightX * halfWidth;
        float rz = rightZ * halfWidth;
        float ux = upX * halfHeight;
        float uy = upY * halfHeight;
        float uz = upZ * halfHeight;

        final float p0x = cx - rx + ux;
        final float p0y = cy + uy;
        final float p0z = cz - rz + uz;
        final float p1x = cx - rx - ux;
        final float p1y = cy - uy;
        final float p1z = cz - rz - uz;
        final float p2x = cx + rx - ux;
        final float p2y = cy - uy;
        final float p2z = cz + rz - uz;
        final float p3x = cx + rx + ux;
        final float p3y = cy + uy;
        final float p3z = cz + rz + uz;

        PoseStack poseStack = new PoseStack();
        logFirstPreviewSubmit(false, textureWidth, textureHeight, camera, anchorX, anchorY, anchorZ,
                route);
        event.getSubmitNodeCollector().submitCustomGeometry(
                poseStack,
                renderType,
                (pose, buffer) -> {
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            false);
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            true);
                });
    }

    static void submitProjectorYuvGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, VideoYuvTextureSet textures) {
        submitProjectorGeometry(event, minecraft, camera, projector,
                yuvRenderTypeForCurrentIrisProgram(textures),
                textures.width(), textures.height());
    }

    /**
     * Submit the current projector frame in the caller's local BER coordinate
     * space.
     *
     * <p>
     * This path does not query {@link Minecraft#level}, so simulated block entities
     * (for example a block carried by
     * an entity renderer) can reuse the already-correct BER pose stack.
     * </p>
     */
    public static boolean submitProjectorFrameOnPose(
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            PoseStack poseStack,
            ProjectorFrameSnapshot frame,
            float halfWidth,
            float halfHeight) {
        if (collector == null || poseStack == null || frame == null || !frame.hasFrame()
                || frame.width() <= 0 || frame.height() <= 0 || halfWidth <= 0.0F || halfHeight <= 0.0F) {
            return false;
        }
        if (frame.yuv()) {
            if (!isCustomYuvShaderAvailable()
                    || frame.yTexture() == null || frame.uTexture() == null || frame.vTexture() == null) {
                return false;
            }
            submitLocalTexturedQuad(collector, poseStack, yuvRenderTypeForSnapshot(frame), halfWidth, halfHeight);
            return true;
        }
        if (frame.rgbaTexture() == null) {
            return false;
        }
        submitLocalTexturedQuad(collector, poseStack,
                frame.emissiveRgba()
                        ? RenderTypes.itemCutout(frame.rgbaTexture())
                        : YuvVideoRenderTypes.videoRgbaEntity(frame.rgbaTexture()),
                halfWidth, halfHeight);
        if (frame.loadingProgressOverlay()) {
            submitLoadingProgressOnPose(collector, poseStack, halfWidth, halfHeight);
        }
        return true;
    }

    public static boolean submitLoadingProgressOnPose(
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            PoseStack poseStack,
            float halfWidth,
            float halfHeight) {
        if (collector == null || poseStack == null || halfWidth <= 0.0F || halfHeight <= 0.0F) {
            return false;
        }
        submitLocalTexturedQuad(collector, poseStack, RenderTypes.itemCutout(LOADING_PROGRESS_FRAME_TEXTURE),
                pixelLeft(LOADING_PROGRESS_X, halfWidth),
                pixelTop(LOADING_PROGRESS_Y, halfHeight),
                pixelRight(LOADING_PROGRESS_X + LOADING_PROGRESS_W, halfWidth),
                pixelBottom(LOADING_PROGRESS_Y + LOADING_PROGRESS_H, halfHeight),
                0.004F);
        int movingX = LOADING_PROGRESS_X + 2 + (int) (((System.nanoTime() / 12_000_000L)
                % Math.max(1, LOADING_PROGRESS_W - LOADING_PROGRESS_SEGMENT_W - 4)));
        submitLocalTexturedQuad(collector, poseStack, RenderTypes.itemCutout(LOADING_PROGRESS_SEGMENT_TEXTURE),
                pixelLeft(movingX, halfWidth),
                pixelTop(LOADING_PROGRESS_Y + 2, halfHeight),
                pixelRight(movingX + LOADING_PROGRESS_SEGMENT_W, halfWidth),
                pixelBottom(LOADING_PROGRESS_Y + 2 + LOADING_PROGRESS_SEGMENT_H, halfHeight),
                0.006F);
        return true;
    }

    public static boolean submitProjectorPrivacyOverlayOnPose(
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            PoseStack poseStack,
            float halfWidth,
            float halfHeight) {
        if (collector == null || poseStack == null || halfWidth <= 0.0F || halfHeight <= 0.0F) {
            return false;
        }
        HolographicPrivacyOverlay.submit(collector, poseStack,
                -halfWidth, halfHeight, 0.003F,
                -halfWidth, -halfHeight, 0.003F,
                halfWidth, -halfHeight, 0.003F,
                halfWidth, halfHeight, 0.003F);
        return true;
    }

    private static void submitLocalTexturedQuad(
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            PoseStack poseStack,
            RenderType renderType,
            float halfWidth,
            float halfHeight) {
        submitLocalTexturedQuad(collector, poseStack, renderType, -halfWidth, halfHeight, halfWidth, -halfHeight,
                0.0F);
    }

    private static void submitLocalTexturedQuad(
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            PoseStack poseStack,
            RenderType renderType,
            float left,
            float top,
            float right,
            float bottom,
            float z) {
        collector.submitCustomGeometry(
                poseStack,
                renderType,
                (pose, buffer) -> {
                    emitQuad(buffer, pose,
                            left, top, z,
                            left, bottom, z,
                            right, bottom, z,
                            right, top, z,
                            false);
                    emitQuad(buffer, pose,
                            left, top, z,
                            left, bottom, z,
                            right, bottom, z,
                            right, top, z,
                            true);
                });
    }

    private static float pixelLeft(int x, float halfWidth) {
        return -halfWidth + x * (halfWidth * 2.0F) / LOADING_PLACEHOLDER_WIDTH;
    }

    private static float pixelRight(int x, float halfWidth) {
        return pixelLeft(x, halfWidth);
    }

    private static float pixelTop(int y, float halfHeight) {
        return halfHeight - y * (halfHeight * 2.0F) / LOADING_PLACEHOLDER_HEIGHT;
    }

    private static float pixelBottom(int y, float halfHeight) {
        return pixelTop(y, halfHeight);
    }

    private static net.minecraft.client.renderer.rendertype.RenderType yuvRenderTypeForSnapshot(
            ProjectorFrameSnapshot frame) {
        if (IrisShaderpackCompat.shouldForceSafeProbeRenderType()
                || IrisShaderpackCompat.shouldUseSingleSamplerProbe()
                || IrisShaderpackCompat.isTexturedProbeProgram()) {
            if (loggedIrisYuvRenderType.compareAndSet(false, true)) {
                LOGGER.info(
                        "Iris/YUV: 首个视频 YUV draw 使用 TEXTURED probe RenderType，绑定 Sampler0/1/2=Y plane，占位规避 shaderpack sampler 校验；非真彩 YUV");
            }
            return YuvVideoRenderTypes.yOnlyTexturedProbeEntity(frame.yTexture());
        }
        if (loggedIrisYuvRenderType.compareAndSet(false, true)) {
            LOGGER.info("Iris/YUV: 首个视频 YUV draw 使用 {} RenderType", frame.format());
        }
        if (frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12) {
            return YuvVideoRenderTypes.nv12Entity(frame.yTexture(), frame.uTexture(), frame.vTexture());
        }
        return YuvVideoRenderTypes.yuv420pEntity(frame.yTexture(), frame.uTexture(), frame.vTexture());
    }

    private static net.minecraft.client.renderer.rendertype.RenderType yuvRenderTypeForCurrentIrisProgram(
            VideoYuvTextureSet textures) {
        if (IrisShaderpackCompat.shouldForceSafeProbeRenderType()
                || IrisShaderpackCompat.shouldUseSingleSamplerProbe()
                || IrisShaderpackCompat.isTexturedProbeProgram()) {
            if (loggedIrisYuvRenderType.compareAndSet(false, true)) {
                LOGGER.info(
                        "Iris/YUV: 首个视频 YUV draw 使用 TEXTURED probe RenderType，绑定 Sampler0/1/2=Y plane，占位规避 shaderpack sampler 校验；非真彩 YUV");
            }
            return YuvVideoRenderTypes.yOnlyTexturedProbeEntity(textures.yId());
        }
        if (loggedIrisYuvRenderType.compareAndSet(false, true)) {
            LOGGER.info("Iris/YUV: 首个视频 YUV draw 使用 {} RenderType", textures.format());
        }
        if (textures.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12) {
            return YuvVideoRenderTypes.nv12Entity(textures.yId(), textures.uId(), textures.vId());
        }
        return YuvVideoRenderTypes.yuv420pEntity(textures.yId(), textures.uId(), textures.vId());
    }

    private static void submitProjectorGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, net.minecraft.client.renderer.rendertype.RenderType renderType,
            int textureWidth, int textureHeight) {
        float scale = Math.abs(projector.getProjectionScale());
        float aspect = textureWidth / (float) textureHeight;
        float halfHeight = HEIGHT * scale * 0.5F;
        float halfWidth = halfHeight * aspect;

        if (!ensureWorldAnchor(minecraft, camera, projector)) {
            return;
        }
        Vec3 cameraPos = camera.position();
        double dx = anchorX - cameraPos.x;
        double dy = anchorY - cameraPos.y;
        double dz = anchorZ - cameraPos.z;
        if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQR) {
            return;
        }
        if (!isScreenInView(camera, anchorX, anchorY, anchorZ)) {
            return;
        }

        double yawRad = Math.toRadians(anchorYawDeg);
        double pitchRad = Math.toRadians(projector.getProjectionPitch());
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

        final float p0x = cx - rx + ux;
        final float p0y = cy + uy;
        final float p0z = cz - rz + uz;
        final float p1x = cx - rx - ux;
        final float p1y = cy - uy;
        final float p1z = cz - rz - uz;
        final float p2x = cx + rx - ux;
        final float p2y = cy - uy;
        final float p2z = cz + rz - uz;
        final float p3x = cx + rx + ux;
        final float p3y = cy + uy;
        final float p3z = cz + rz + uz;

        PoseStack poseStack = new PoseStack();
        logFirstPreviewSubmit(true, textureWidth, textureHeight, camera, anchorX, anchorY, anchorZ,
                "projector-yuv");
        event.getSubmitNodeCollector().submitCustomGeometry(
                poseStack,
                renderType,
                (pose, buffer) -> {
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            false);
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            true);
                });
    }

    private static void logFirstPreviewSubmit(boolean yuv, int textureWidth, int textureHeight, Camera camera,
            double centerX, double centerY, double centerZ, String route) {
        if (firstPreviewSubmitLogged) {
            return;
        }
        firstPreviewSubmitLogged = true;
        Vec3 cameraPos = camera.position();
        double dx = centerX - cameraPos.x;
        double dy = centerY - cameraPos.y;
        double dz = centerZ - cameraPos.z;
        LOGGER.debug("视频 quad 已提交: route={}, yuv={}, size={}x{}, distance={}, "
                + "anchor=({}, {}, {}), camera=({}, {}, {}), shaderAvailable={}, yuvTextureSet={}", route, yuv,
                textureWidth, textureHeight,
                String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(dx * dx + dy * dy + dz * dz)),
                String.format(java.util.Locale.ROOT, "%.2f", centerX),
                String.format(java.util.Locale.ROOT, "%.2f", centerY),
                String.format(java.util.Locale.ROOT, "%.2f", centerZ),
                String.format(java.util.Locale.ROOT, "%.2f", cameraPos.x),
                String.format(java.util.Locale.ROOT, "%.2f", cameraPos.y),
                String.format(java.util.Locale.ROOT, "%.2f", cameraPos.z),
                isCustomYuvShaderAvailable(), yuvTextureSet != null);
    }

    private static boolean ensureWorldAnchor(Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector) {
        if (projector != null) {
            BlockPos pos = projector.getBlockPos();
            anchorX = pos.getX() + 0.5D + projector.getProjectionDistanceX();
            anchorY = pos.getY() + projector.getProjectionHeight();
            anchorZ = pos.getZ() + 0.5D + projector.getProjectionDistanceZ();
            anchorYawDeg = projector.getProjectionYaw();
            anchorInitialized = true;
            return true;
        }
        if (activeRequiresProjector) {
            // 正式投影仪会话在 TP/区块重载的一两帧里可能暂时拿不到 BE。
            // 这时绝不能退回到“玩家前方测试面”，否则只有本客户端会看到屏幕跟着玩家跑。
            anchorInitialized = false;
            return false;
        }
        if (anchorInitialized) {
            return true;
        }
        Player player = minecraft.player;
        if (player == null) {
            Vec3 pos = camera.position();
            anchorX = pos.x;
            anchorY = pos.y;
            anchorZ = pos.z;
            anchorYawDeg = 0.0F;
            anchorInitialized = true;
            return true;
        }
        double yawRad = Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        anchorX = player.getX() + forwardX * WORLD_ANCHOR_DISTANCE;
        anchorY = player.getEyeY();
        anchorZ = player.getZ() + forwardZ * WORLD_ANCHOR_DISTANCE;
        // 屏幕横向轴由 yaw 推导，投影面朝向玩家当前视线方向。
        anchorYawDeg = player.getYRot();
        anchorInitialized = true;
        LOGGER.debug("视频投影测试面已锚定到世界坐标: ({}, {}, {}), yaw={}",
                String.format(java.util.Locale.ROOT, "%.2f", anchorX),
                String.format(java.util.Locale.ROOT, "%.2f", anchorY),
                String.format(java.util.Locale.ROOT, "%.2f", anchorZ),
                String.format(java.util.Locale.ROOT, "%.1f", anchorYawDeg));
        return true;
    }

    private static void observeCameraContinuity(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) {
            cameraContinuityInitialized = false;
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 pos = camera.position();
        String dimension = minecraft.level.dimension().identifier().toString();
        if (!cameraContinuityInitialized) {
            rememberCameraPosition(pos, dimension);
            return;
        }
        double dx = pos.x - lastCameraX;
        double dy = pos.y - lastCameraY;
        double dz = pos.z - lastCameraZ;
        if (!dimension.equals(lastCameraDimension)
                || dx * dx + dy * dy + dz * dz > CAMERA_TELEPORT_RESET_DISTANCE_SQR) {
            resetLocalRenderAnchors();
        }
        rememberCameraPosition(pos, dimension);
    }

    private static void rememberCameraPosition(Vec3 pos, String dimension) {
        lastCameraX = pos.x;
        lastCameraY = pos.y;
        lastCameraZ = pos.z;
        lastCameraDimension = dimension;
        cameraContinuityInitialized = true;
    }

    private static void resetLocalRenderAnchors() {
        anchorInitialized = false;
        firstImmediateQuadLogged = false;
        PROJECTOR_VISIBILITY_CACHE.clear();
    }

    private static VideoProjectorBlockEntity activeVideoProjector(Minecraft minecraft) {
        BlockPos projectorPos = activeProjectorPos;
        if (projectorPos == null || minecraft.level == null) {
            return null;
        }
        return minecraft.level.getBlockEntity(projectorPos) instanceof VideoProjectorBlockEntity projector
                ? projector
                : null;
    }

    private static List<VideoProjectorBlockEntity> activeVideoProjectors(Minecraft minecraft) {
        if (minecraft.level == null) {
            return List.of();
        }
        List<VideoProjectorBlockEntity> projectors = new ArrayList<>();
        for (BlockPos pos : activeProjectorPositions) {
            if (minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector) {
                projectors.add(projector);
            }
        }
        activeProjectorPositions
                .removeIf(pos -> !(minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity));
        activeProjectorPos = projectors.isEmpty() ? null : projectors.get(0).getBlockPos().immutable();
        return projectors;
    }

    private static void emitQuad(VertexConsumer buffer, PoseStack.Pose pose,
            float p0x, float p0y, float p0z,
            float p1x, float p1y, float p1z,
            float p2x, float p2y, float p2z,
            float p3x, float p3y, float p3z,
            boolean reverse) {
        if (reverse) {
            vertex(buffer, pose, p3x, p3y, p3z, 1.0F, 0.0F);
            vertex(buffer, pose, p2x, p2y, p2z, 1.0F, 1.0F);
            vertex(buffer, pose, p1x, p1y, p1z, 0.0F, 1.0F);
            vertex(buffer, pose, p0x, p0y, p0z, 0.0F, 0.0F);
        } else {
            vertex(buffer, pose, p0x, p0y, p0z, 0.0F, 0.0F);
            vertex(buffer, pose, p1x, p1y, p1z, 0.0F, 1.0F);
            vertex(buffer, pose, p2x, p2y, p2z, 1.0F, 1.0F);
            vertex(buffer, pose, p3x, p3y, p3z, 1.0F, 0.0F);
        }
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float u,
            float v) {
        RenderVertexUtils.texturedVertex(buffer, pose, x, y, z, u, v);
    }

    static boolean isProjectorScreenRenderable(Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, double dotThreshold) {
        if (minecraft == null || camera == null || projector == null) {
            return false;
        }
        BlockPos projectorPos = projector.getBlockPos().immutable();
        long nowNs = System.nanoTime();
        int thresholdKey = (int) Math.round(dotThreshold * 1000.0D);
        VisibilitySample cached = PROJECTOR_VISIBILITY_CACHE.get(projectorPos);
        if (cached != null && cached.thresholdKey() == thresholdKey
                && nowNs - cached.createdNanoTime() <= Math.max(0L, VIEW_OCCLUSION_CACHE_NANOS)) {
            return cached.visible();
        }
        boolean visible = computeProjectorScreenRenderable(minecraft, camera, projector, dotThreshold);
        PROJECTOR_VISIBILITY_CACHE.put(projectorPos, new VisibilitySample(nowNs, thresholdKey, visible));
        return visible;
    }

    private static boolean computeProjectorScreenRenderable(Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, double dotThreshold) {
        BlockPos pos = projector.getBlockPos();
        double centerX = pos.getX() + 0.5D + projector.getProjectionDistanceX();
        double centerY = pos.getY() + projector.getProjectionHeight();
        double centerZ = pos.getZ() + 0.5D + projector.getProjectionDistanceZ();
        Vec3 cameraPos = camera.position();
        double dx = centerX - cameraPos.x;
        double dy = centerY - cameraPos.y;
        double dz = centerZ - cameraPos.z;
        if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQR) {
            return false;
        }
        for (Vec3 sample : projectorVisibilitySamples(projector, centerX, centerY, centerZ)) {
            if (isScreenInView(camera, sample.x, sample.y, sample.z, dotThreshold)
                    && !isOccluded(minecraft, cameraPos, sample, pos)) {
                return true;
            }
        }
        return false;
    }

    private static List<Vec3> projectorVisibilitySamples(VideoProjectorBlockEntity projector,
            double centerX, double centerY, double centerZ) {
        float scale = Math.abs(projector.getProjectionScale());
        double halfHeight = HEIGHT * scale * 0.5D * VIEW_SAMPLE_EDGE_SCALE;
        double halfWidth = halfHeight * 16.0D / 9.0D;
        double yawRad = Math.toRadians(projector.getProjectionYaw());
        double pitchRad = Math.toRadians(projector.getProjectionPitch());
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double upX = forwardX * Math.sin(pitchRad);
        double upY = Math.cos(pitchRad);
        double upZ = forwardZ * Math.sin(pitchRad);
        Vec3 center = new Vec3(centerX, centerY, centerZ);
        Vec3 right = new Vec3(rightX * halfWidth, 0.0D, rightZ * halfWidth);
        Vec3 up = new Vec3(upX * halfHeight, upY * halfHeight, upZ * halfHeight);
        return List.of(center,
                center.add(right).add(up),
                center.add(right).subtract(up),
                center.subtract(right).add(up),
                center.subtract(right).subtract(up));
    }

    private static boolean isOccluded(Minecraft minecraft, Vec3 cameraPos, Vec3 target, BlockPos projectorPos) {
        if (!VIEW_OCCLUSION_CHECK || minecraft.level == null) {
            return false;
        }
        BlockHitResult hit = minecraft.level.clip(new ClipContext(cameraPos, target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return false;
        }
        if (hit.getBlockPos().equals(projectorPos)) {
            return false;
        }
        return hit.getLocation().distanceToSqr(cameraPos) + 1.0e-4D < target.distanceToSqr(cameraPos);
    }

    private record VisibilitySample(long createdNanoTime, int thresholdKey, boolean visible) {
    }

    static double viewDotThreshold() {
        return VIEW_DOT_THRESHOLD;
    }

    private static boolean isScreenInView(Camera camera, double centerX, double centerY, double centerZ) {
        return isScreenInView(camera, centerX, centerY, centerZ, VIEW_DOT_THRESHOLD);
    }

    private static boolean isScreenInView(Camera camera, double centerX, double centerY, double centerZ,
            double dotThreshold) {
        Vec3 cameraPos = camera.position();
        double dx = centerX - cameraPos.x;
        double dy = centerY - cameraPos.y;
        double dz = centerZ - cameraPos.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 1.0e-4D) {
            return true;
        }
        Vector3fc forward = camera.forwardVector();
        double dot = (dx / len) * forward.x() + (dy / len) * forward.y() + (dz / len) * forward.z();
        return dot > dotThreshold;
    }

}
