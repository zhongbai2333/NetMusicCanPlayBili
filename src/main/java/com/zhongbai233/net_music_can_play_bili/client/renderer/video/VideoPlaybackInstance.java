package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.media.stream.MediaNetworkFailureClassifier;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseExecutor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个同步视频播放会话，负责解码线程、会话专属动态纹理和投影仪列表
 */
final class VideoPlaybackInstance {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * 可选视频相位补偿。默认不补偿，避免掩盖 OpenAL 音频 pacing 问题；需要按设备/驱动微调时再通过
     * JVM 参数启用。
     */
    private static final long AUDIO_OUTPUT_LATENCY_COMPENSATION_MILLIS = Long.getLong(
            "bili.video.pipeline.audio_latency_compensation_ms", 0L);
    private static final long CHASE_WINDOW_MILLIS = Long.getLong("ncpb.video.pipeline.chase_window_ms", 10_000L);
    private static final long SLOWDOWN_WINDOW_MILLIS = Long.getLong("ncpb.video.pipeline.slowdown_window_ms", 2_500L);
    private static final boolean OFFSCREEN_PAUSE_DECODE = Boolean.parseBoolean(
            System.getProperty("ncpb.video.offscreen.pause_decode", "true"));
    private static final long OFFSCREEN_GRACE_NANOS = Long.getLong("ncpb.video.offscreen.grace_ms", 500L)
            * 1_000_000L;
    private static final long OFFSCREEN_RESUME_RESTART_LAG_NANOS = Long.getLong(
            "bili.video.offscreen.resume_restart_lag_ms", 1_500L) * 1_000_000L;
    private static final double OFFSCREEN_PREWARM_DOT_THRESHOLD = Double.parseDouble(
            System.getProperty("ncpb.video.offscreen.prewarm_dot_threshold", "-0.20"));
    private static final double IRIS_WARNING_PLACEHOLDER_VIEW_DEPTH_OFFSET = Double.parseDouble(
            System.getProperty("ncpb.video.pipeline.iris_warning_placeholder_view_depth_offset", "0.03"));
    private static final float IRIS_WARNING_PLACEHOLDER_LOCAL_DEPTH_OFFSET = Float.parseFloat(
            System.getProperty("ncpb.video.pipeline.iris_warning_placeholder_local_depth_offset", "-0.01"));
    private static final Identifier[] LOADING_PLACEHOLDER_TEXTURES = new Identifier[] {
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                    "textures/gui/video_loading/loading_base_phase0.png"),
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                    "textures/gui/video_loading/loading_base_phase1.png"),
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                    "textures/gui/video_loading/loading_base_phase2.png"),
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                    "textures/gui/video_loading/loading_base_phase3.png")
    };
    private static final Identifier IRIS_WARNING_PLACEHOLDER_TEXTURE = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/video_loading/iris_translucent_warning_base.png");
    private static final Identifier NETWORK_ERROR_PLACEHOLDER_TEXTURE = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/video_loading/network_error_base.png");
    private static final boolean NETWORK_ERROR_PLACEHOLDER_ENABLED = Boolean.parseBoolean(
            System.getProperty("ncpb.video.pipeline.network_error_placeholder", "true"));
    private static final int LOADING_PLACEHOLDER_WIDTH = 320;
    private static final int LOADING_PLACEHOLDER_HEIGHT = 180;

    private final String videoUrl;
    private int targetWidth;
    private int targetHeight;
    private final int fps;
    private final int codecId;
    private final String sessionId;
    private final long startOffsetMillis;
    private final long totalMillis;
    private final boolean preferNative;
    private final String decoderOverride;
    private final Identifier firstTextureId;
    private final Identifier secondTextureId;
    private final Identifier yTextureId;
    private final Identifier uTextureId;
    private final Identifier vTextureId;
    private final VideoPlaybackAnchor anchor;
    private final VideoFrameQueue frameQueue = new VideoFrameQueue(
            Integer.getInteger("ncpb.video.pipeline.queue_capacity", 3));
    private final AtomicLong generation = new AtomicLong();
    private final Set<BlockPos> projectorPositions = new CopyOnWriteArraySet<>();
    private volatile boolean running;
    private volatile boolean hasFrame;
    private volatile long startNanoTime;
    private volatile Thread decodeThread;
    private volatile AutoCloseable decoder;
    private volatile CompletableFuture<Void> decodeExit = CompletableFuture.completedFuture(null);
    private volatile DynamicTexture frontTexture;
    private volatile DynamicTexture backTexture;
    private volatile VideoYuvTextureSet yuvTextureSet;
    private volatile Identifier frontTextureId;
    private volatile Identifier backTextureId;
    private volatile boolean firstFrameLogged;
    private volatile boolean firstSubmitLogged;
    private volatile boolean firstYuvImmediateLogged;
    private volatile long firstDecodedNanoTime;
    private volatile boolean startupBufferReady;
    private volatile long lastUploadPumpNanoTime;
    private volatile long decoderStartOffsetMillis;
    private volatile long lastUploadedPtsNanos = -1L;
    private volatile long adaptiveRestartOffsetMillis = -1L;
    private volatile long lastVisibleNanoTime;
    private volatile long offscreenSinceNanoTime;
    private volatile boolean prewarmVisible = true;
    private volatile boolean loggedOffscreenPause;
    private volatile boolean networkFailure;
    private volatile boolean networkFailureNotified;
    private volatile boolean guiConsumer;
    private int consecutiveBadUploads;

    VideoPlaybackInstance(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> projectorPositions,
            BlockPos turntablePos, boolean preferNative, String decoderOverride) {
        this(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, totalMillis,
                projectorPositions, VideoPlaybackAnchor.turntable(turntablePos, sessionId, Math.max(0L, totalMillis)),
                preferNative, decoderOverride);
    }

    VideoPlaybackInstance(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> projectorPositions,
            VideoPlaybackAnchor anchor, boolean preferNative, String decoderOverride) {
        this.videoUrl = videoUrl;
        this.targetWidth = Math.max(1, targetWidth);
        this.targetHeight = Math.max(1, targetHeight);
        this.fps = Math.max(1, fps);
        this.codecId = codecId;
        this.sessionId = sessionId;
        this.startOffsetMillis = Math.max(0L, startOffsetMillis);
        this.totalMillis = Math.max(0L, totalMillis);
        this.preferNative = preferNative;
        this.decoderOverride = decoderOverride;
        this.anchor = anchor != null ? anchor : VideoPlaybackAnchor.turntable(null, this.sessionId, this.totalMillis);
        String textureSuffix = Integer.toUnsignedString(sessionId.hashCode(), 16);
        this.firstTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + textureSuffix + "_a");
        this.secondTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + textureSuffix + "_b");
        this.yTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + textureSuffix + "_y");
        this.uTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + textureSuffix + "_u");
        this.vTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/bili_video_preview_" + textureSuffix + "_v");
        this.frontTextureId = firstTextureId;
        this.backTextureId = secondTextureId;
        replaceProjectors(projectorPositions);
        LOGGER.info("视频会话创建: session={}, {}x{} @ {}fps, renderBackend={}, decodeFormat={}", sessionId,
                this.targetWidth, this.targetHeight, this.fps, VideoBillboardPreview.RENDER_BACKEND,
                VideoBillboardPreview.YUV_DECODE_BACKEND
                        ? (VideoBillboardPreview.isCustomYuvShaderAvailable()
                                ? VideoBillboardPreview.yuvDecodeFormat().name() + "→RGB(shader)"
                                : VideoBillboardPreview.yuvDecodeFormat().name() + "→RGBA(cpu/iris-fallback)")
                        : "RGBA");
    }

    void start() {
        running = true;
        hasFrame = false;
        firstFrameLogged = false;
        firstSubmitLogged = false;
        firstYuvImmediateLogged = false;
        firstDecodedNanoTime = 0L;
        startupBufferReady = false;
        lastUploadPumpNanoTime = System.nanoTime();
        decoderStartOffsetMillis = startOffsetMillis;
        lastUploadedPtsNanos = -1L;
        adaptiveRestartOffsetMillis = -1L;
        lastVisibleNanoTime = System.nanoTime();
        offscreenSinceNanoTime = 0L;
        prewarmVisible = true;
        loggedOffscreenPause = false;
        networkFailure = false;
        networkFailureNotified = false;
        consecutiveBadUploads = 0;
        frameQueue.clear();
        startNanoTime = System.nanoTime();
        long gen = generation.incrementAndGet();
        startDecodeThread(gen, "bili-video-" + sessionId);
    }

    private void startDecodeThread(long gen, String threadName) {
        CompletableFuture<Void> exit = new CompletableFuture<>();
        decodeExit = exit;
        Thread thread = new Thread(() -> {
            try {
                decode(gen);
            } finally {
                exit.complete(null);
            }
        }, threadName);
        thread.setDaemon(true);
        decodeThread = thread;
        thread.start();
    }

    private void decode(long gen) {
        long frameIntervalNs = Math.max(1L, 1_000_000_000L / fps);
        long frameIndex = 0L;
        long effectiveStartOffsetMillis = effectiveDecoderStartOffsetMillis();
        decoderStartOffsetMillis = effectiveStartOffsetMillis;
        adaptiveRestartOffsetMillis = -1L;
        try (AutoCloseable dec = VideoBillboardPreview.openDecoder(videoUrl, targetWidth, targetHeight, fps, codecId,
                preferNative, decoderOverride, effectiveStartOffsetMillis, totalMillis, guiConsumer)) {
            decoder = dec;
            while (running && gen == generation.get()) {
                if (!waitWhilePaused(gen)) {
                    break;
                }
                if (!hasVideoConsumer()) {
                    break;
                }
                if (!waitWhileOffscreen(gen)) {
                    break;
                }
                if (frameIndex > 0L) {
                    waitForDecodeLead(frameIntervalNs, gen);
                }
                long waitStartNs = System.nanoTime();
                VideoBillboardPreview.DecodedFrame frame = VideoBillboardPreview.nextDecodedFrame(dec);
                long waitNs = System.nanoTime() - waitStartNs;
                if (frameIndex == 0L && waitNs > 2_000_000_000L) {
                    LOGGER.warn("视频流水线首帧等待耗时较长: session={}, wait={}ms, startOffset={}ms, queue={}",
                            sessionId, waitNs / 1_000_000L, effectiveStartOffsetMillis, frameQueue.size());
                }
                if (frame == null) {
                    break;
                }
                frameIndex++;
                long ptsNanos = frame.ptsNanos() >= 0L ? frame.ptsNanos() : frameIndex * frameIntervalNs;
                if (!firstFrameLogged && shouldDropStaleStartupFrame(ptsNanos)) {
                    frame.close();
                    continue;
                }
                if (!firstFrameLogged) {
                    firstFrameLogged = true;
                    firstDecodedNanoTime = System.nanoTime();
                }
                if (!frameQueue.offer(new DecodedVideoFrame(frameIndex, ptsNanos, frame),
                        () -> running && gen == generation.get())) {
                    frame.close();
                    break;
                }
                warnIfUploadPumpStalled();
            }
        } catch (OutOfMemoryError error) {
            com.zhongbai233.net_music_can_play_bili.client.ClientMediaLifecycleHandler
                .tripMemoryProtection("video decoder allocation failed: " + error.getMessage());
            LOGGER.error("视频会话内存分配失败并触发熔断: session={}", sessionId, error);
        } catch (Exception e) {
            if (gen != generation.get() || (!running && isInterruptedWait(e))) {
                return;
            }
            networkFailure = MediaNetworkFailureClassifier.isNetworkFailure(e);
            if (networkFailure) {
                notifyNetworkFailure();
            }
            LOGGER.error("视频会话解码失败: session={}", sessionId, e);
        } finally {
            if (gen == generation.get()) {
                running = false;
                decoder = null;
            }
        }
    }

    private boolean shouldDropStaleStartupFrame(long ptsNanos) {
        long maxStartupLagNs = Long.getLong("ncpb.video.pipeline.startup_drop_lag_ms", 750L) * 1_000_000L;
        if (maxStartupLagNs <= 0L) {
            return false;
        }
        long playbackNs = playbackNanos();
        boolean drop = playbackNs - ptsNanos > maxStartupLagNs;
        return drop && frameQueue.isEmpty();
    }

    private void waitForDecodeLead(long frameIntervalNs, long gen) throws InterruptedException {
        long maxLeadNs = Math.max(frameIntervalNs * frameQueue.capacity(),
                Long.getLong("ncpb.video.pipeline.max_decode_lead_ms", 250L) * 1_000_000L);
        while (running && gen == generation.get() && frameQueue.isFull()
                && frameQueue.latestPtsNanos() - playbackNanos() > maxLeadNs) {
            warnIfUploadPumpStalled();
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(5L);
        }
    }

    private void warnIfUploadPumpStalled() {
        long thresholdNs = Long.getLong("ncpb.video.pipeline.upload_pump_warn_ms", 1000L) * 1_000_000L;
        long idleNs = System.nanoTime() - lastUploadPumpNanoTime;
        if (thresholdNs > 0L && frameQueue.isFull() && idleNs > thresholdNs) {
            lastUploadPumpNanoTime = System.nanoTime();
            LOGGER.warn("视频流水线上传泵疑似停滞: session={}, queue={}, latestPts={}ms, clock={}ms, idle={}ms",
                    sessionId, frameQueue.size(), frameQueue.latestPtsNanos() / 1_000_000L,
                    playbackNanos() / 1_000_000L, idleNs / 1_000_000L);
        }
    }

    private boolean waitWhilePaused(long gen) {
        if (!isGamePaused()) {
            return running && gen == generation.get();
        }
        long pauseStartNs = System.nanoTime();
        while (running && gen == generation.get() && isGamePaused()) {
            try {
                java.util.concurrent.TimeUnit.MILLISECONDS.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        startNanoTime += Math.max(0L, System.nanoTime() - pauseStartNs);
        return running && gen == generation.get();
    }

    private boolean waitWhileOffscreen(long gen) {
        if (!OFFSCREEN_PAUSE_DECODE || !isOffscreenPauseActive()) {
            return running && gen == generation.get();
        }
        long pauseStartNs = System.nanoTime();
        if (!loggedOffscreenPause) {
            loggedOffscreenPause = true;
            LOGGER.debug("视频会话离屏暂停取帧: session={}, queue={}, media={}ms, master={}ms",
                    sessionId, frameQueue.size(), mediaMillis(), anchor.timeline().mediaMillis());
        }
        while (running && gen == generation.get() && isOffscreenPauseActive()) {
            try {
                java.util.concurrent.TimeUnit.MILLISECONDS.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        long pausedNs = System.nanoTime() - pauseStartNs;
        if (pausedNs > 0L) {
            LOGGER.debug("视频会话离屏恢复取帧: session={}, paused={}ms, media={}ms, master={}ms",
                    sessionId, pausedNs / 1_000_000L, mediaMillis(), anchor.timeline().mediaMillis());
        }
        return running && gen == generation.get();
    }

    private boolean isOffscreenPauseActive() {
        if (prewarmVisible) {
            return false;
        }
        long lastVisible = lastVisibleNanoTime;
        return lastVisible > 0L && System.nanoTime() - lastVisible > Math.max(0L, OFFSCREEN_GRACE_NANOS);
    }

    private static boolean isGamePaused() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.isPaused();
    }

    private static boolean isInterruptedWait(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                return true;
            }
            if (current instanceof IOException && current.getMessage() != null
                    && current.getMessage().contains("等待 native 视频帧时被中断")) {
                return true;
            }
        }
        return false;
    }

    private long playbackNanos() {
        long synced = syncedPlaybackNanos();
        if (synced >= 0L) {
            return synced;
        }
        long startNs = startNanoTime;
        return startNs > 0L ? Math.max(0L, System.nanoTime() - startNs) : 0L;
    }

    private long syncedPlaybackNanos() {
        long restartOffset = adaptiveRestartOffsetMillis;
        long requestedOffsetMillis = restartOffset >= 0L ? restartOffset : startOffsetMillis;
        long baseOffsetMillis = Math.max(requestedOffsetMillis, decoderStartOffsetMillis);
        long synced = anchor.timeline().relativeNanos(
                baseOffsetMillis + AUDIO_OUTPUT_LATENCY_COMPENSATION_MILLIS);
        return synced;
    }

    private long effectiveDecoderStartOffsetMillis() {
        long restartOffset = adaptiveRestartOffsetMillis;
        long requestedOffsetMillis = restartOffset >= 0L ? restartOffset : startOffsetMillis;
        long synced = anchor.timeline().mediaMillis();
        long offset = synced >= 0L ? Math.max(requestedOffsetMillis, synced) : requestedOffsetMillis;
        return totalMillis > 0L ? Math.min(totalMillis, offset) : offset;
    }

    void pumpUploadOnRenderThread() {
        lastUploadPumpNanoTime = System.nanoTime();
        if (OFFSCREEN_PAUSE_DECODE && isOffscreenPauseActive()) {
            return;
        }
        if (!running && frameQueue.isEmpty()) {
            return;
        }
        if (!startupBufferReady && shouldWaitForStartupBuffer()) {
            return;
        }
        startupBufferReady = true;
        long playbackNs = playbackNanos();
        DecodedVideoFrame frame = frameQueue.pollBestFrame(playbackNs,
                Long.getLong("ncpb.video.pipeline.early_tolerance_ms", 12L) * 1_000_000L);
        if (frame == null) {
            return;
        }
        long maxVisibleLagNs = Long.getLong("ncpb.video.pipeline.max_visible_lag_ms", 250L) * 1_000_000L;
        if (maxVisibleLagNs > 0L && playbackNs - frame.ptsNanos() > maxVisibleLagNs) {
            DecodedVideoFrame chase = frameQueue.pollBestFrame(playbackNs, 0L);
            if (chase != null) {
                frame.close();
                frame = chase;
            }
        }
        boolean uploaded = false;
        long uploadNs = 0L;
        try {
            long uploadStartNs = System.nanoTime();
            uploaded = uploadDecodedFrameOnRenderThread(frame.frame());
            uploadNs = System.nanoTime() - uploadStartNs;
            if (uploaded) {
                lastUploadedPtsNanos = frame.ptsNanos();
            }
        } catch (OutOfMemoryError error) {
            com.zhongbai233.net_music_can_play_bili.client.ClientMediaLifecycleHandler
                    .tripMemoryProtection("video texture allocation failed: " + error.getMessage());
            LOGGER.error("视频纹理内存分配失败并触发熔断: session={}", sessionId, error);
        } finally {
            frame.close();
        }
        if (uploaded) {
            recordAdaptiveUploadCost(uploadNs);
        }
    }

    private void recordAdaptiveUploadCost(long uploadNs) {
        if (uploadNs > VideoBillboardPreview.ADAPTIVE_FRAME_BUDGET_NS) {
            consecutiveBadUploads++;
            if (consecutiveBadUploads >= VideoBillboardPreview.ADAPTIVE_BAD_FRAME_THRESHOLD) {
                requestAdaptiveDownscale();
            }
        } else {
            consecutiveBadUploads = Math.max(0, consecutiveBadUploads - 2);
        }
    }

    private synchronized boolean requestAdaptiveDownscale() {
        int currentWidth = targetWidth;
        int currentHeight = targetHeight;
        if (currentWidth <= VideoBillboardPreview.MIN_ADAPTIVE_WIDTH) {
            return false;
        }
        int nextWidth = Math.max(VideoBillboardPreview.MIN_ADAPTIVE_WIDTH, Math.round(currentWidth * 0.75F));
        int nextHeight = Math.max(1, Math.round(currentHeight * (nextWidth / (float) currentWidth)));
        if (nextWidth >= currentWidth || nextHeight >= currentHeight) {
            return false;
        }

        long restartOffsetMillis = currentRestartOffsetMillis();
        LOGGER.warn("视频会话上传持续超预算，优先保证游戏流畅: session={}, {}x{} -> {}x{}，offset={}ms",
                sessionId, currentWidth, currentHeight, nextWidth, nextHeight, restartOffsetMillis);
        restartDecoder(nextWidth, nextHeight, restartOffsetMillis);
        return true;
    }

    private long currentRestartOffsetMillis() {
        long displayed = mediaMillis();
        if (displayed >= 0L) {
            return totalMillis > 0L ? Math.min(totalMillis, displayed) : displayed;
        }
        long synced = anchor.timeline().mediaMillis();
        if (synced >= 0L) {
            return totalMillis > 0L ? Math.min(totalMillis, synced) : synced;
        }
        long base = Math.max(startOffsetMillis, decoderStartOffsetMillis);
        long elapsed = Math.max(0L, playbackNanos() / 1_000_000L);
        long value = Math.max(0L, base + elapsed);
        return totalMillis > 0L ? Math.min(totalMillis, value) : value;
    }

    private void restartDecoder(int nextWidth, int nextHeight, long restartOffsetMillis) {
        restartDecoder(nextWidth, nextHeight, restartOffsetMillis, false);
    }

    private void restartDecoder(int nextWidth, int nextHeight, long restartOffsetMillis, boolean keepVisibleFrame) {
        long gen = generation.incrementAndGet();
        boolean preserveVisibleFrame = keepVisibleFrame
                && nextWidth == targetWidth
                && nextHeight == targetHeight
                && hasFrame
                && (frontTexture != null || yuvTextureSet != null);
        AutoCloseable oldDecoder = decoder;
        decoder = null;
        CompletableFuture<Void> oldDecodeExit = decodeExit;
        CompletableFuture<Void> nativeTermination = CompletableFuture.completedFuture(null);
        if (oldDecoder instanceof com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder nativeDecoder) {
            nativeDecoder.requestClose();
            nativeTermination = nativeDecoder.terminationFuture();
        }
        Thread oldThread = decodeThread;
        if (oldThread != null) {
            oldThread.interrupt();
        }
        CompletableFuture<Void> closeCompletion = MediaCloseExecutor.closeAsync(oldDecoder,
                "adaptive video decoder " + sessionId);

        frameQueue.clear();
        if (!preserveVisibleFrame) {
            releaseTexture();
        }
        targetWidth = nextWidth;
        targetHeight = nextHeight;
        hasFrame = preserveVisibleFrame;
        firstFrameLogged = false;
        firstDecodedNanoTime = 0L;
        startupBufferReady = false;
        networkFailure = false;
        networkFailureNotified = false;
        startNanoTime = System.nanoTime();
        lastUploadPumpNanoTime = System.nanoTime();
        decoderStartOffsetMillis = Math.max(0L, restartOffsetMillis);
        adaptiveRestartOffsetMillis = decoderStartOffsetMillis;
        if (!preserveVisibleFrame) {
            lastUploadedPtsNanos = -1L;
        }
        consecutiveBadUploads = 0;
        // Keep the session active while the old native worker drains so periodic sync
        // packets cannot
        // replace this instance. The generation guard prevents the old worker from
        // publishing state.
        running = true;
        CompletableFuture.allOf(closeCompletion, nativeTermination, oldDecodeExit).thenRun(() -> {
            if (gen != generation.get() || !running || !hasVideoConsumer()) {
                return;
            }
            startDecodeThread(gen, preserveVisibleFrame
                    ? "bili-video-" + sessionId + "-resume"
                    : "bili-video-" + sessionId + "-adaptive");
        });
    }

    private boolean shouldWaitForStartupBuffer() {
        int requiredFrames = Integer.getInteger("ncpb.video.pipeline.startup_prebuffer_frames", 2);
        if (requiredFrames <= 1 || hasFrame) {
            return false;
        }
        if (frameQueue.size() >= requiredFrames) {
            return false;
        }
        long firstDecodedNs = firstDecodedNanoTime;
        if (firstDecodedNs <= 0L) {
            return false;
        }
        long maxWaitNs = Long.getLong("ncpb.video.pipeline.startup_prebuffer_max_wait_ms", 250L) * 1_000_000L;
        boolean wait = System.nanoTime() - firstDecodedNs < maxWaitNs;
        if (!wait) {
            return false;
        }
        return wait;
    }

    private boolean uploadOnRenderThread(byte[] rgba) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || rgba.length < targetWidth * targetHeight * 4) {
            return false;
        }
        ensureTexture();
        NativeImage image = backTexture.getPixels();
        if (image == null || image.isClosed()) {
            return false;
        }
        VideoFrameUploader.uploadRgba(image, rgba, targetWidth, targetHeight);
        backTexture.upload();
        swapTextures();
        releaseYuvTextures();
        hasFrame = true;
        return true;
    }

    private boolean uploadDecodedFrameOnRenderThread(VideoBillboardPreview.DecodedFrame frame) {
        if (VideoBillboardPreview.isCustomYuvShaderAvailable()
                && isYuvFrameFormat(frame.format())) {
            return uploadYuvOnRenderThread(frame);
        }
        return uploadOnRenderThread(Yuv420pConverter.toUploadRgba(frame, targetWidth, targetHeight));
    }

    private boolean uploadYuvOnRenderThread(VideoBillboardPreview.DecodedFrame frame) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        ensureYuvTextureSet(frame.format());
        if (!uploadYuvFrameData(frame)) {
            return false;
        }
        releaseRgbaTextures();
        hasFrame = true;
        return true;
    }

    private boolean uploadYuvFrameData(VideoBillboardPreview.DecodedFrame frame) {
        if (yuvTextureSet == null || frame == null || yuvTextureSet.format() != frame.format()) {
            return false;
        }
        java.nio.ByteBuffer buffer = frame.buffer();
        if (buffer != null) {
            return yuvTextureSet.upload(buffer, frame.byteLength(), targetWidth, targetHeight);
        }
        return yuvTextureSet.upload(frame.data(), targetWidth, targetHeight);
    }

    private void ensureYuvTextureSet(
            com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
        com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format normalized = format == com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                ? com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                : com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
        if (yuvTextureSet != null && yuvTextureSet.format() == normalized) {
            return;
        }
        if (yuvTextureSet != null) {
            yuvTextureSet.close();
        }
        if (normalized == com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P) {
            yuvTextureSet = new Yuv420pTextureSet(yTextureId, uTextureId, vTextureId,
                    "bili_video_" + sessionId + "_yuv420p");
        } else {
            yuvTextureSet = new Nv12TextureSet(yTextureId, uTextureId, yTextureId,
                    "bili_video_" + sessionId + "_nv12");
        }
    }

    private static boolean isYuvFrameFormat(
            com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
        return format == com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                || format == com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
    }

    private void releaseRgbaTextures() {
        if (frontTexture != null) {
            Minecraft.getInstance().getTextureManager().release(frontTextureId);
            frontTexture.close();
            frontTexture = null;
        }
        if (backTexture != null && !backTextureId.equals(frontTextureId)) {
            Minecraft.getInstance().getTextureManager().release(backTextureId);
            backTexture.close();
            backTexture = null;
        } else if (backTexture != null) {
            backTexture.close();
            backTexture = null;
        }
        frontTextureId = firstTextureId;
        backTextureId = secondTextureId;
    }

    private void releaseYuvTextures() {
        if (yuvTextureSet != null) {
            yuvTextureSet.close();
            yuvTextureSet = null;
        }
    }

    private void ensureTexture() {
        if (frontTexture != null && backTexture != null) {
            NativeImage image = frontTexture.getPixels();
            NativeImage backImage = backTexture.getPixels();
            if (image != null && !image.isClosed() && image.getWidth() == targetWidth
                    && image.getHeight() == targetHeight
                    && backImage != null && !backImage.isClosed() && backImage.getWidth() == targetWidth
                    && backImage.getHeight() == targetHeight) {
                return;
            }
        }
        releaseTexture();
        frontTexture = new DynamicTexture("bili_video_" + sessionId + "_front", targetWidth, targetHeight, false);
        backTexture = new DynamicTexture("bili_video_" + sessionId + "_back", targetWidth, targetHeight, false);
        frontTextureId = firstTextureId;
        backTextureId = secondTextureId;
        Minecraft.getInstance().getTextureManager().register(frontTextureId, frontTexture);
        Minecraft.getInstance().getTextureManager().register(backTextureId, backTexture);
    }

    private void swapTextures() {
        DynamicTexture oldFront = frontTexture;
        frontTexture = backTexture;
        backTexture = oldFront;
        Identifier oldFrontId = frontTextureId;
        frontTextureId = backTextureId;
        backTextureId = oldFrontId;
    }

    VideoBillboardPreview.ProjectorFrameSnapshot frameSnapshot(BlockPos projectorPos) {
        if (projectorPos != null && !projectorPositions.contains(projectorPos)) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        if (networkFailure && NETWORK_ERROR_PLACEHOLDER_ENABLED) {
            return placeholderSnapshot(PlaceholderKind.NETWORK_ERROR);
        }
        if (!hasFrame) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        return currentFrameSnapshot();
    }

    VideoBillboardPreview.ProjectorFrameSnapshot displayFrameSnapshot(BlockPos projectorPos) {
        if (projectorPos != null && !projectorPositions.contains(projectorPos)) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        boolean loadingPlaceholderEnabled = Boolean.parseBoolean(
                System.getProperty("ncpb.video.pipeline.loading_placeholder", "true"));
        // Iris shaderpack 会捕获 BER 提交的多平面 YUV 几何；直接把这类帧交给
        // VideoProjectorRenderer 会造成不可见颜色写入或随视角变化的深度闪烁。
        // 与旧的全局提交路径保持一致：该兼容模式下用明确的警告占位图替代 YUV
        // 面片，而不是先因 hasFrame 返回实际视频，导致下方警告分支永远不可达。
        if (networkFailure && NETWORK_ERROR_PLACEHOLDER_ENABLED) {
            return placeholderSnapshot(PlaceholderKind.NETWORK_ERROR);
        }
        boolean irisWarning = shouldShowIrisTranslucencyWarning();
        if (irisWarning && loadingPlaceholderEnabled) {
            return placeholderSnapshot(PlaceholderKind.IRIS_WARNING);
        }
        if (hasFrame) {
            return irisWarning ? VideoBillboardPreview.ProjectorFrameSnapshot.empty() : currentFrameSnapshot();
        }
        if (!loadingPlaceholderEnabled) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        return placeholderSnapshot(PlaceholderKind.LOADING);
    }

    private VideoBillboardPreview.ProjectorFrameSnapshot placeholderSnapshot(PlaceholderKind kind) {
        if (kind == PlaceholderKind.LOADING) {
            return loadingPlaceholderSnapshot(startNanoTime);
        }
        boolean irisWarning = kind == PlaceholderKind.IRIS_WARNING;
        return new VideoBillboardPreview.ProjectorFrameSnapshot(true, false, placeholderTexture(kind),
                null,
                null, null,
                com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA,
                LOADING_PLACEHOLDER_WIDTH, LOADING_PLACEHOLDER_HEIGHT,
                // Iris guard 会主动跳过有已知 sampler 问题的 item_cutout draw。警告图是
                // 完整不透明画面，应走 ENTITY_SOLID RGBA 兼容路径；普通加载图仍保留
                // emissive/cutout 及进度层。警告图作为 immediate 视频的 RGBA 回退底片，
                // 正常写入世界深度，但沿 BER 局部屏幕法线稍微后退，避免与视频共面。
                !irisWarning, kind == PlaceholderKind.LOADING,
                irisWarning ? IRIS_WARNING_PLACEHOLDER_LOCAL_DEPTH_OFFSET : 0.0F);
    }

    static VideoBillboardPreview.ProjectorFrameSnapshot loadingPlaceholderSnapshot(long startedNanoTime) {
        long elapsedNs = Math.max(0L, System.nanoTime() - startedNanoTime);
        int phase = (int) ((elapsedNs / 300_000_000L) % LOADING_PLACEHOLDER_TEXTURES.length);
        return new VideoBillboardPreview.ProjectorFrameSnapshot(true, false, LOADING_PLACEHOLDER_TEXTURES[phase],
                null, null, null,
                com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA,
                LOADING_PLACEHOLDER_WIDTH, LOADING_PLACEHOLDER_HEIGHT, true, true, 0.0F);
    }

    VideoBillboardPreview.ProjectorFrameSnapshot turntableFrameSnapshot(BlockPos turntablePos) {
        if (turntablePos == null || !anchor.isForTurntable(turntablePos)) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        if (networkFailure && NETWORK_ERROR_PLACEHOLDER_ENABLED) {
            return placeholderSnapshot(PlaceholderKind.NETWORK_ERROR);
        }
        if (!hasFrame) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        return currentFrameSnapshot();
    }

    private VideoBillboardPreview.ProjectorFrameSnapshot currentFrameSnapshot() {
        if (frontTexture != null) {
            return new VideoBillboardPreview.ProjectorFrameSnapshot(true, false, frontTextureId, null, null, null,
                    com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA,
                    targetWidth, targetHeight, false, false, 0.0F);
        }
        if (yuvTextureSet != null) {
            return new VideoBillboardPreview.ProjectorFrameSnapshot(true, true, null, yuvTextureSet.yId(),
                    yuvTextureSet.uId(), yuvTextureSet.vId(), yuvTextureSet.format(), yuvTextureSet.width(),
                    yuvTextureSet.height(), false, false, 0.0F);
        }
        return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
    }

    VideoBillboardPreview.ProjectorFrameSnapshot previewFrameSnapshot() {
        if (networkFailure && NETWORK_ERROR_PLACEHOLDER_ENABLED) {
            return placeholderSnapshot(PlaceholderKind.NETWORK_ERROR);
        }
        if (!hasFrame) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        return currentFrameSnapshot();
    }

    void submit(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera) {
        if (!firstSubmitLogged) {
            firstSubmitLogged = true;
        }
        boolean renderable = false;
        boolean prewarm = false;
        for (BlockPos pos : projectorPositions) {
            if (VideoBillboardPreview.isProjectorRenderedByBer(pos)) {
                renderable = true;
                prewarm = true;
                continue;
            }
            if (!(minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector)) {
                // 矿车内的模拟方块实体不属于 Minecraft.level。不能因真实世界查询不到它，
                // 就在某一渲染帧永久删除 consumer；卸载和解绑由 stopIfProjector 明确处理。
                continue;
            }
            boolean projectorRenderable = VideoBillboardPreview.isProjectorScreenRenderable(minecraft, camera,
                    projector, VideoBillboardPreview.viewDotThreshold());
            boolean projectorPrewarm = projectorRenderable || VideoBillboardPreview.isProjectorScreenRenderable(
                    minecraft, camera, projector, OFFSCREEN_PREWARM_DOT_THRESHOLD);
            renderable |= projectorRenderable;
            prewarm |= projectorPrewarm;
        }
        boolean holographicVisible = hasHolographicTurntableConsumer();
        markVisibility(renderable || holographicVisible, prewarm || holographicVisible);
        pumpUploadOnRenderThread();
        for (BlockPos pos : projectorPositions) {
            if (VideoBillboardPreview.isProjectorRenderedByBer(pos)) {
                continue;
            }
            if (!(minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector)) {
                continue;
            }
            if (HolographicGlassesClient.shouldHideProjectorVideos()) {
                VideoBillboardPreview.submitProjectorPrivacyOverlay(event, minecraft, camera, projector);
            } else if (networkFailure && NETWORK_ERROR_PLACEHOLDER_ENABLED) {
                VideoBillboardPreview.submitProjectorEmissiveGeometry(event, minecraft, camera, projector,
                        placeholderTexture(PlaceholderKind.NETWORK_ERROR), LOADING_PLACEHOLDER_WIDTH,
                        LOADING_PLACEHOLDER_HEIGHT);
            } else if (hasFrame && frontTexture != null) {
                VideoBillboardPreview.submitProjectorGeometry(event, minecraft, camera, projector, frontTextureId,
                        targetWidth, targetHeight);
            } else if (hasFrame && yuvTextureSet != null && VideoBillboardPreview.isCustomYuvShaderAvailable()
                    && !VideoBillboardPreview.shouldDrawYuvImmediateWithIris()) {
                VideoBillboardPreview.submitProjectorYuvGeometry(event, minecraft, camera, projector, yuvTextureSet);
            } else if (Boolean.parseBoolean(System.getProperty("ncpb.video.pipeline.loading_placeholder", "true"))) {
                PlaceholderKind kind = shouldShowIrisTranslucencyWarning()
                        ? PlaceholderKind.IRIS_WARNING
                        : PlaceholderKind.LOADING;
                boolean irisWarning = kind == PlaceholderKind.IRIS_WARNING;
                if (irisWarning && IRIS_WARNING_PLACEHOLDER_VIEW_DEPTH_OFFSET > 0.0D) {
                    VideoBillboardPreview.submitProjectorViewDepthOffsetGeometry(event, minecraft, camera, projector,
                            placeholderTexture(kind), LOADING_PLACEHOLDER_WIDTH,
                            LOADING_PLACEHOLDER_HEIGHT,
                            IRIS_WARNING_PLACEHOLDER_VIEW_DEPTH_OFFSET);
                } else {
                    VideoBillboardPreview.submitProjectorEmissiveGeometry(event, minecraft, camera, projector,
                            placeholderTexture(kind), LOADING_PLACEHOLDER_WIDTH,
                            LOADING_PLACEHOLDER_HEIGHT);
                }
            }
        }
    }

    private void markVisibility(boolean renderable, boolean prewarm) {
        long nowNs = System.nanoTime();
        prewarmVisible = prewarm;
        if (renderable || prewarm) {
            long offscreenSince = offscreenSinceNanoTime;
            lastVisibleNanoTime = nowNs;
            offscreenSinceNanoTime = 0L;
            if (offscreenSince > 0L) {
                maybeRestartForVisibleResume(nowNs - offscreenSince);
            }
            loggedOffscreenPause = false;
            return;
        }
        if (offscreenSinceNanoTime == 0L) {
            offscreenSinceNanoTime = nowNs;
        }
    }

    private void maybeRestartForVisibleResume(long offscreenDurationNs) {
        if (!running || OFFSCREEN_RESUME_RESTART_LAG_NANOS <= 0L) {
            return;
        }
        long masterMillis = anchor.timeline().mediaMillis();
        if (masterMillis < 0L) {
            return;
        }
        long queuedMillis = queuedMediaMillis();
        long displayedMillis = mediaMillis();
        long bestVideoMillis = Math.max(queuedMillis, displayedMillis);
        long lagNs = bestVideoMillis >= 0L ? (masterMillis - bestVideoMillis) * 1_000_000L : offscreenDurationNs;
        if (lagNs < OFFSCREEN_RESUME_RESTART_LAG_NANOS) {
            return;
        }
        long restartOffsetMillis = totalMillis > 0L ? Math.min(totalMillis, masterMillis) : masterMillis;
        LOGGER.debug("视频会话离屏恢复重定位: session={}, offscreen={}ms, master={}ms, video={}ms, offset={}ms",
                sessionId, offscreenDurationNs / 1_000_000L, masterMillis, bestVideoMillis, restartOffsetMillis);
        restartDecoder(targetWidth, targetHeight, restartOffsetMillis, true);
    }

    void renderYuvImmediate(RenderLevelStageEvent event, String route) {
        if ((networkFailure && NETWORK_ERROR_PLACEHOLDER_ENABLED)
                || !hasFrame || yuvTextureSet == null || !VideoBillboardPreview.isCustomYuvShaderAvailable()
                || !VideoBillboardPreview.shouldDrawYuvImmediateWithIris()) {
            return;
        }
        pumpUploadOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        boolean drew = false;
        boolean prewarm = false;
        for (BlockPos pos : projectorPositions) {
            if (VideoBillboardPreview.drawCapturedProjectorYuvImmediate(event, sessionId, pos, yuvTextureSet,
                    route)) {
                drew = true;
                prewarm = true;
                continue;
            }
            if (!(minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector)) {
                continue;
            }
            prewarm |= VideoBillboardPreview.isProjectorScreenRenderable(minecraft, camera, projector,
                    OFFSCREEN_PREWARM_DOT_THRESHOLD);
            if (HolographicGlassesClient.shouldHideProjectorVideos()) {
                VideoBillboardPreview.drawProjectorPrivacyOverlayImmediate(event, minecraft, camera, projector, route);
                drew = true;
            } else {
                drew |= VideoBillboardPreview.drawProjectorYuvImmediate(event, minecraft, camera, projector,
                        yuvTextureSet, route);
            }
        }
        markVisibility(drew, prewarm);
        if (drew && !firstYuvImmediateLogged) {
            firstYuvImmediateLogged = true;
            LOGGER.warn("Iris/YUV: session={} 的投影仪 YUV 使用实例纹理 immediate 绘制，route={}, texture={}x{}",
                    sessionId, route, yuvTextureSet.width(), yuvTextureSet.height());
        }
    }

    private Identifier placeholderTexture(PlaceholderKind kind) {
        if (kind == PlaceholderKind.NETWORK_ERROR) {
            return NETWORK_ERROR_PLACEHOLDER_TEXTURE;
        }
        if (kind == PlaceholderKind.IRIS_WARNING) {
            return IRIS_WARNING_PLACEHOLDER_TEXTURE;
        }
        long elapsedNs = Math.max(0L, System.nanoTime() - startNanoTime);
        int phase = (int) ((elapsedNs / 300_000_000L) % LOADING_PLACEHOLDER_TEXTURES.length);
        return LOADING_PLACEHOLDER_TEXTURES[phase];
    }

    private enum PlaceholderKind {
        LOADING,
        IRIS_WARNING,
        NETWORK_ERROR
    }

    private boolean shouldShowIrisTranslucencyWarning() {
        return hasFrame && yuvTextureSet != null && VideoBillboardPreview.shouldDrawYuvImmediateWithIris();
    }

    boolean isWithinAudioRange(Minecraft minecraft) {
        if (minecraft.player == null || !hasVideoConsumer()) {
            return false;
        }
        return anchor.isWithinAudioRange(minecraft, projectorPositions, VideoBillboardPreview.AUDIO_SYNC_RANGE_SQR);
    }

    boolean isRunningAtOffset(long requestedOffsetMillis) {
        return isRunningAtOffset(requestedOffsetMillis, 1_500L);
    }

    boolean isRunningAtOffset(long requestedOffsetMillis, long toleranceMillis) {
        if (!running) {
            return false;
        }
        long tolerance = Math.max(0L, toleranceMillis);
        if (hasFrame) {
            long elapsedMillis = Math.max(0L, (System.nanoTime() - startNanoTime) / 1_000_000L);
            long estimatedDisplayedMillis = totalMillis > 0L
                    ? Math.min(totalMillis, startOffsetMillis + elapsedMillis)
                    : startOffsetMillis + elapsedMillis;
            if (Math.abs(estimatedDisplayedMillis - Math.max(0L, requestedOffsetMillis)) < tolerance) {
                return true;
            }
        }
        if (!hasFrame) {
            // 解码器仍在 seek/解码第一帧可见画面。这里先认为会话兼容，避免周期性投影刷新或服务端同步包
            // 在产出第一帧前反复杀掉解码器。
            return true;
        }
        long expectedOffset = Math.max(startOffsetMillis, decoderStartOffsetMillis);
        return Math.abs(expectedOffset - Math.max(0L, requestedOffsetMillis)) < tolerance;
    }

    boolean canChaseToOffset(long requestedOffsetMillis) {
        if (!running) {
            return false;
        }
        if (!hasFrame) {
            // 首帧还在加载/seek：此时最容易被 3 秒同步包误杀，必须继续复用当前解码器。
            return true;
        }
        long target = totalMillis > 0L ? Math.min(totalMillis, Math.max(0L, requestedOffsetMillis))
                : Math.max(0L, requestedOffsetMillis);
        long current = mediaMillis();
        if (current < 0L) {
            return true;
        }
        long delta = target - current;
        if (delta >= 0L) {
            long queuedTargetMillis = Math.max(startOffsetMillis, decoderStartOffsetMillis)
                    + Math.max(0L, frameQueue.latestPtsNanos() / 1_000_000L);
            return target <= queuedTargetMillis + Math.max(0L, CHASE_WINDOW_MILLIS);
        }
        return -delta <= Math.max(0L, SLOWDOWN_WINDOW_MILLIS);
    }

    void replaceProjectors(Collection<BlockPos> positions) {
        projectorPositions.clear();
        projectorPositions.addAll(VideoBillboardPreview.immutablePositions(positions));
    }

    void addProjector(BlockPos pos) {
        if (pos != null) {
            projectorPositions.add(pos.immutable());
        }
    }

    void removeProjector(BlockPos pos) {
        projectorPositions.remove(pos);
    }

    boolean isForTurntable(BlockPos pos) {
        return anchor.isForTurntable(pos);
    }

    boolean isSession(String candidateSessionId) {
        return sessionId.equals(candidateSessionId != null ? candidateSessionId : "");
    }

    boolean hasProjectors() {
        return !projectorPositions.isEmpty();
    }

    boolean hasVideoConsumer() {
        return guiConsumer || !projectorPositions.isEmpty() || hasHolographicTurntableConsumer();
    }

    void setGuiConsumer(boolean value) {
        guiConsumer = value;
        if (value) {
            prewarmVisible = true;
            offscreenSinceNanoTime = 0L;
            lastVisibleNanoTime = System.nanoTime();
        }
    }

    boolean hasGuiConsumer() {
        return guiConsumer;
    }

    private boolean hasHolographicTurntableConsumer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return false;
        }
        for (HolographicGlassesItem.ScreenBinding binding : HolographicGlassesClient.screenBindings()) {
            if (binding.source() != null && binding.source().isTurntable()
                    && minecraft.level.dimension().equals(binding.source().dimension())
                    && anchor.isForTurntable(binding.source().pos())) {
                return true;
            }
        }
        return false;
    }

    boolean containsProjector(BlockPos pos) {
        return pos != null && projectorPositions.contains(pos);
    }

    boolean isRunning() {
        return running;
    }

    boolean hasFrame() {
        return hasFrame;
    }

    boolean hasNetworkFailure() {
        return networkFailure;
    }

    synchronized boolean retryNetworkFailure() {
        if (!networkFailure || !hasVideoConsumer()) {
            return false;
        }
        long retryOffsetMillis = currentRestartOffsetMillis();
        long syncedOffsetMillis = anchor.timeline().mediaMillis();
        if (syncedOffsetMillis >= 0L) {
            retryOffsetMillis = Math.max(retryOffsetMillis, syncedOffsetMillis);
            if (totalMillis > 0L) {
                retryOffsetMillis = Math.min(totalMillis, retryOffsetMillis);
            }
        }
        LOGGER.info("手动重试视频网络连接: session={}, offset={}ms", sessionId, retryOffsetMillis);
        restartDecoder(targetWidth, targetHeight, retryOffsetMillis, hasFrame);
        return true;
    }

    private void notifyNetworkFailure() {
        if (networkFailureNotified) {
            return;
        }
        networkFailureNotified = true;
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (!networkFailure || minecraft.player == null) {
                return;
            }
            Component retry = Component.literal("[重试视频]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.GOLD)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.RunCommand("/ncpbc video retry"))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击重新连接视频流"))));
            minecraft.player.sendSystemMessage(Component.literal("视频网络连接失败 ")
                    .withStyle(ChatFormatting.RED)
                    .append(retry));
        });
    }

    long mediaMillis() {
        long uploadedPts = lastUploadedPtsNanos;
        if (!hasFrame || uploadedPts < 0L) {
            return -1L;
        }
        long clockMillis = Math.max(0L, uploadedPts / 1_000_000L);
        long baseOffsetMillis = Math.max(startOffsetMillis, decoderStartOffsetMillis);
        long value = Math.max(0L, baseOffsetMillis + clockMillis - AUDIO_OUTPUT_LATENCY_COMPENSATION_MILLIS);
        return totalMillis > 0L ? Math.min(totalMillis, value) : value;
    }

    long queuedMediaMillis() {
        long latestPts = frameQueue.latestPtsNanos();
        if (latestPts <= 0L) {
            return -1L;
        }
        long baseOffsetMillis = Math.max(startOffsetMillis, decoderStartOffsetMillis);
        long value = Math.max(0L, baseOffsetMillis + latestPts / 1_000_000L);
        return totalMillis > 0L ? Math.min(totalMillis, value) : value;
    }

    VideoBillboardPreview.VideoStatus status() {
        return new VideoBillboardPreview.VideoStatus(targetWidth, targetHeight, fps, hasFrame, true);
    }

    void stop() {
        running = false;
        generation.incrementAndGet();
        frameQueue.clear();
        AutoCloseable dec = decoder;
        decoder = null;
        if (dec != null) {
            MediaCloseExecutor.closeAsync(dec, "video decoder " + sessionId);
        }
        Thread thread = decodeThread;
        if (thread != null) {
            thread.interrupt();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            releaseTexture();
        } else {
            minecraft.execute(this::releaseTexture);
        }
    }

    private void releaseTexture() {
        releaseRgbaTextures();
        releaseYuvTextures();
    }

    private record DecodedVideoFrame(long frameIndex, long ptsNanos, VideoBillboardPreview.DecodedFrame frame) {
        void close() {
            frame.close();
        }
    }

    private static final class VideoFrameQueue {
        private final int capacity;
        private final ArrayDeque<DecodedVideoFrame> frames = new ArrayDeque<>();
        private long latestPtsNanos;

        VideoFrameQueue(int capacity) {
            this.capacity = Math.max(1, capacity);
        }

        synchronized boolean offer(DecodedVideoFrame frame, java.util.function.BooleanSupplier shouldContinue)
                throws InterruptedException {
            while (frames.size() >= capacity && shouldContinue.getAsBoolean()) {
                wait(5L);
            }
            if (!shouldContinue.getAsBoolean()) {
                return false;
            }
            latestPtsNanos = Math.max(latestPtsNanos, frame.ptsNanos());
            frames.addLast(frame);
            notifyAll();
            return true;
        }

        synchronized DecodedVideoFrame pollBestFrame(long playbackNanos, long earlyToleranceNanos) {
            DecodedVideoFrame best = null;
            long visibleUntil = playbackNanos + Math.max(0L, earlyToleranceNanos);
            while (!frames.isEmpty()) {
                DecodedVideoFrame next = frames.peekFirst();
                if (next.ptsNanos() > visibleUntil) {
                    break;
                }
                DecodedVideoFrame polled = frames.pollFirst();
                if (best != null) {
                    best.close();
                }
                best = polled;
            }
            if (best != null) {
                notifyAll();
            }
            return best;
        }

        synchronized void clear() {
            for (DecodedVideoFrame frame : frames) {
                frame.close();
            }
            frames.clear();
            notifyAll();
        }

        synchronized boolean isFull() {
            return frames.size() >= capacity;
        }

        synchronized boolean isEmpty() {
            return frames.isEmpty();
        }

        synchronized int size() {
            return frames.size();
        }

        int capacity() {
            return capacity;
        }

        synchronized long latestPtsNanos() {
            return latestPtsNanos;
        }
    }
}