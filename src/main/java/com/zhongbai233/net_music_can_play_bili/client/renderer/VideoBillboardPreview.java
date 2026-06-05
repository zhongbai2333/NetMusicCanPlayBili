package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureFlags;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.joml.Vector3fc;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * 该类负责管理视频解码会话，将解码后的 RGBA 帧上传为 {@link DynamicTexture}，
 * 并在 {@link SubmitCustomGeometryEvent} 中把纹理提交为世界空间 billboard。正式播放时，
 * billboard 会锚定到视频投影仪的配置位置，并根据投影仪生命周期、播放会话、视距与视角进行裁剪
 * </p>
 */
@EventBusSubscriber(modid = NetMusicCanPlayBili.MODID, value = Dist.CLIENT)
public final class VideoBillboardPreview {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "dynamic/bili_video_preview");
    private static final Identifier PACKED_BENCH_TEXTURE_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "dynamic/bili_video_packed_bench");
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final int MIN_ADAPTIVE_WIDTH = VideoFeatureFlags.advancedInt("bili.video.adaptive.min_width", 640);
    private static final long ADAPTIVE_FRAME_BUDGET_NS = VideoFeatureFlags
            .advancedLong("bili.video.adaptive.frame_budget_ms", 12L) * 1_000_000L;
    private static final int ADAPTIVE_BAD_FRAME_THRESHOLD = VideoFeatureFlags
            .advancedInt("bili.video.adaptive.bad_frames", 45);
    static final int MAX_CATCH_UP_DROPS_PER_TICK = VideoFeatureFlags.advancedInt(
            "bili.video.sync.max_drop_frames", 12);
    private static final double DISTANCE = 3.0D;
    private static final float HEIGHT = 1.35F;
    private static final boolean CPU_BARS = Boolean.getBoolean("bili.video.cpu_bars");
    private static final boolean WORLD_ANCHORED = Boolean.parseBoolean(
            System.getProperty("bili.video.world_anchor", "true"));
    private static final double WORLD_ANCHOR_DISTANCE = Double.parseDouble(
            System.getProperty("bili.video.world_anchor.distance", "6.0"));
    static final double AUDIO_SYNC_RANGE_SQR = Math.pow(Double.parseDouble(
            System.getProperty("bili.video.turntable.sync_range", "96.0")), 2.0);
    private static final double VIEW_DOT_THRESHOLD = Double.parseDouble(
            System.getProperty("bili.video.render.view_dot_threshold", "0.12"));
    private static final double MAX_RENDER_DISTANCE_SQR = Math.pow(Double.parseDouble(
            System.getProperty("bili.video.max_render_distance", "64.0")), 2.0);

    private static final Map<String, VideoPlaybackInstance> INSTANCES = new ConcurrentHashMap<>();

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final AtomicLong decodeGeneration = new AtomicLong();
    private static volatile boolean running;
    private static volatile int width;
    private static volatile int height;
    private static volatile int activeFps;
    private static volatile long activeStartOffsetMillis;
    private static volatile long activeStartNanoTime;
    private static volatile boolean hasFrame;
    private static DynamicTexture texture;
    private static DynamicTexture packedBenchTexture;
    private static volatile boolean anchorInitialized;
    private static volatile double anchorX;
    private static volatile double anchorY;
    private static volatile double anchorZ;
    private static volatile float anchorYawDeg;
    private static volatile Thread decodeThread;
    private static volatile AutoCloseable activeDecoder;
    private static volatile String activeSessionId = "";
    private static volatile BlockPos activeProjectorPos;
    private static final Set<BlockPos> activeProjectorPositions = new CopyOnWriteArraySet<>();
    private static volatile boolean activeRequiresProjector;
    private static volatile PlaybackRequest activeRequest;

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
                null);
    }

    public static void startPreviewAt(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, boolean preferNative, String decoderOverride) {
        startInternal(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride,
                sessionId == null ? "" : sessionId, Math.max(0L, startOffsetMillis), Math.max(0L, totalMillis),
                null);
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
                anchorPositions, null, preferNative, decoderOverride);
    }

    public static void startSynced(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> anchorPositions,
            BlockPos turntablePos, boolean preferNative, String decoderOverride) {
        if (sessionId != null && !sessionId.isBlank()) {
            startOrUpdateInstance(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis,
                    totalMillis, anchorPositions, turntablePos, preferNative, decoderOverride);
            return;
        }
        startInternal(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, sessionId,
                startOffsetMillis, totalMillis, anchorPositions);
    }

    private static void startOrUpdateInstance(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> anchorPositions,
            BlockPos turntablePos, boolean preferNative, String decoderOverride) {
        List<BlockPos> projectors = immutablePositions(anchorPositions);
        if (projectors.isEmpty()) {
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
                normalizedOffset, Math.max(0L, totalMillis), projectors, turntablePos, preferNative, decoderOverride);
        INSTANCES.put(sessionId, instance);
        instance.start();
    }

    private static void startInternal(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, String sessionId, long startOffsetMillis,
            long totalMillis, Collection<BlockPos> anchorPositions) {
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
        activeFps = Math.max(1, fps);
        activeStartOffsetMillis = normalizedOffset;
        activeStartNanoTime = System.nanoTime();
        activeSessionId = normalizedSession;
        replaceActiveProjectors(anchorPositions);
        activeProjectorPos = activeProjectorPositions.stream().findFirst().orElse(null);
        activeRequiresProjector = !normalizedSession.isBlank() && !activeProjectorPositions.isEmpty();
        activeRequest = new PlaybackRequest(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative,
                decoderOverride, normalizedSession, startOffsetMillis, totalMillis, activeProjectorPositions);
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

        Thread thread = new Thread(() -> decodeLoop(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative,
                decoderOverride, startOffsetMillis, totalMillis, generation),
                "bili-video-billboard-preview");
        thread.setDaemon(true);
        decodeThread = thread;
        thread.start();
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
        activeRequest = null;
        activeRequiresProjector = false;
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
        INSTANCES.values().forEach(instance -> instance.removeProjector(projectorPos));
        INSTANCES.entrySet().removeIf(entry -> {
            if (entry.getValue().hasProjectors()) {
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
        for (VideoPlaybackInstance instance : INSTANCES.values()) {
            if (instance.isForTurntable(turntablePos)) {
                instance.addProjector(projectorPos);
            }
        }
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
                Long.getLong("bili.video.pipeline.chase_window_ms", 10_000L));
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
            boolean preferNative, String decoderOverride, long startOffsetMillis, long totalMillis, long generation) {
        if (CPU_BARS) {
            decodeCpuBarsLoop(targetWidth, targetHeight, fps);
            return;
        }
        long frameIntervalNs = fps > 0 ? Math.max(1L, 1_000_000_000L / fps) : 50_000_000L;
        long frameIndex = 0L;
        int consecutiveBadFrames = 0;
        try (AutoCloseable decoder = openDecoder(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative,
                decoderOverride, startOffsetMillis, totalMillis)) {
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
                byte[] frame = nextFrame(decoder);
                if (frame == null) {
                    break;
                }
                frameIndex++;

                int dropped = 0;
                long nowNs = System.nanoTime();
                while (running && generation == decodeGeneration.get()
                        && nowNs - expectedFrameTimeNs(frameIndex, frameIntervalNs) > frameIntervalNs * 2L
                        && dropped < MAX_CATCH_UP_DROPS_PER_TICK) {
                    byte[] catchUpFrame = nextFrame(decoder);
                    if (catchUpFrame == null) {
                        frame = null;
                        break;
                    }
                    frame = catchUpFrame;
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
                long uploadNs = uploadFrameSync(frame, targetWidth, targetHeight, generation);
                if (uploadNs < 0L) {
                    LOGGER.warn("视频 billboard 预览上传失败或客户端世界已退出");
                    break;
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
        if (preferNative) {
            IOException last = null;
            for (String hwaccel : VideoFeatureFlags.requestedHwaccelCandidates()) {
                try {
                    return new Fmp4NativeVideoDecoder(videoUrl, codecId, targetWidth, targetHeight,
                            Integer.MAX_VALUE, true, false, hwaccel, startOffsetMillis, totalMillis, fps);
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
                    req.decoderOverride(), req.sessionId(), elapsed, req.totalMillis(), req.anchorPositions());
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
        private final byte[] rgba;
        private final AutoCloseable delegate;
        private final long ptsNanos;

        private DecodedFrame(byte[] rgba, AutoCloseable delegate) {
            this(rgba, delegate, -1L);
        }

        private DecodedFrame(byte[] rgba, AutoCloseable delegate, long ptsNanos) {
            this.rgba = rgba;
            this.delegate = delegate;
            this.ptsNanos = ptsNanos;
        }

        static DecodedFrame wrap(byte[] rgba) {
            return rgba != null ? new DecodedFrame(rgba, null) : null;
        }

        static DecodedFrame wrap(Fmp4NativeVideoDecoder.DecodedFrame frame) {
            return frame != null ? new DecodedFrame(frame.rgba(), frame, frame.ptsNanos()) : null;
        }

        byte[] rgba() {
            return rgba;
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
            long totalMillis, List<BlockPos> anchorPositions, long startedNanoTime) {
        PlaybackRequest(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
                boolean preferNative, String decoderOverride, String sessionId, long startOffsetMillis,
                long totalMillis,
                Collection<BlockPos> anchorPositions) {
            this(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, sessionId,
                    startOffsetMillis, totalMillis, immutablePositions(anchorPositions), System.nanoTime());
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
        int frameSize = targetWidth * targetHeight * 4;
        long frameDelayMs = fps > 0 ? Math.max(1L, 1000L / fps) : 50L;
        long frameIndex = 0L;
        LOGGER.info("视频 billboard 使用 CPU 纯色彩条诊断模式: {}x{} @ {}fps", targetWidth, targetHeight, fps);
        try {
            while (running) {
                byte[] frame = new byte[frameSize];
                fillCpuBars(frame, targetWidth, targetHeight, frameIndex++);
                Minecraft.getInstance().execute(() -> uploadFrame(frame, targetWidth, targetHeight));
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

    private static void fillCpuBars(byte[] frame, int frameWidth, int frameHeight, long frameIndex) {
        int phase = (int) (frameIndex % Math.max(1, frameWidth));
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                int bar = (x * 8) / Math.max(1, frameWidth);
                int r;
                int g;
                int b;
                switch (bar) {
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
                int i = (y * frameWidth + x) * 4;
                frame[i] = (byte) r;
                frame[i + 1] = (byte) g;
                frame[i + 2] = (byte) b;
                frame[i + 3] = (byte) 255;
            }
        }
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

    private static long uploadFrameSync(byte[] rgba, int frameWidth, int frameHeight, long generation) {
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
            boolean ok = uploadFrameOnRenderThread(rgba, frameWidth, frameHeight);
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
                if (!instance.isWithinAudioRange(minecraft)) {
                    instance.stop();
                    return true;
                }
                instance.submit(event, minecraft, camera);
                return !instance.isRunning() && !instance.hasFrame();
            });
        }
        if (!hasFrame || texture == null || width <= 0 || height <= 0) {
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
                submitProjectorGeometry(event, minecraft, camera, projector);
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
            ensureWorldAnchor(minecraft, camera, projector);
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
        event.getSubmitNodeCollector().submitCustomGeometry(
                poseStack,
                RenderTypes.entityCutout(TEXTURE_ID),
                (pose, buffer) -> {
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            false);
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            true);
                });
    }

    private static void submitProjectorGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector) {
        submitProjectorGeometry(event, minecraft, camera, projector, TEXTURE_ID, width, height);
    }

    static void submitProjectorGeometry(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, Identifier renderTextureId, int textureWidth, int textureHeight) {
        float scale = Math.abs(projector.getProjectionScale());
        float aspect = textureWidth / (float) textureHeight;
        float halfHeight = HEIGHT * scale * 0.5F;
        float halfWidth = halfHeight * aspect;

        ensureWorldAnchor(minecraft, camera, projector);
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
        event.getSubmitNodeCollector().submitCustomGeometry(
                poseStack,
                RenderTypes.entityCutout(renderTextureId),
                (pose, buffer) -> {
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            false);
                    emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                            true);
                });
    }

    private static void ensureWorldAnchor(Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector) {
        if (projector != null) {
            BlockPos pos = projector.getBlockPos();
            double yawRad = Math.toRadians(projector.getProjectionYaw());
            double rightX = Math.cos(yawRad);
            double rightZ = Math.sin(yawRad);
            anchorX = pos.getX() + 0.5D + rightX * projector.getProjectionDistance();
            anchorY = pos.getY() + projector.getProjectionHeight();
            anchorZ = pos.getZ() + 0.5D + rightZ * projector.getProjectionDistance();
            anchorYawDeg = projector.getProjectionYaw();
            anchorInitialized = true;
            return;
        }
        if (activeRequiresProjector) {
            stop();
            return;
        }
        if (anchorInitialized) {
            return;
        }
        Player player = minecraft.player;
        if (player == null) {
            Vec3 pos = camera.position();
            anchorX = pos.x;
            anchorY = pos.y;
            anchorZ = pos.z;
            anchorYawDeg = 0.0F;
            anchorInitialized = true;
            return;
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
        LOGGER.info("视频投影测试面已锚定到世界坐标: ({}, {}, {}), yaw={}",
                String.format(java.util.Locale.ROOT, "%.2f", anchorX),
                String.format(java.util.Locale.ROOT, "%.2f", anchorY),
                String.format(java.util.Locale.ROOT, "%.2f", anchorZ),
                String.format(java.util.Locale.ROOT, "%.1f", anchorYawDeg));
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
        buffer.addVertex(pose, x, y, z)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }

    private static boolean isScreenInView(Camera camera, double centerX, double centerY, double centerZ) {
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
        return dot > VIEW_DOT_THRESHOLD;
    }

}
