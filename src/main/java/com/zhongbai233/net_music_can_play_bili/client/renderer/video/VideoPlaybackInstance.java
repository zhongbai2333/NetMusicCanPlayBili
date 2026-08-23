package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.VideoCandidate;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ClientDisplayProperties;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseExecutor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个同步视频播放会话，负责解码线程、会话专属动态纹理和投影仪列表
 */
final class VideoPlaybackInstance {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final VideoPipelineProperties.Timing TIMING = VideoPipelineProperties.timing();
    private static final VideoPipelineProperties.Presentation PRESENTATION = VideoPipelineProperties.presentation();
    /**
     * 可选视频相位补偿。默认不补偿，避免掩盖 OpenAL 音频 pacing 问题；需要按设备/驱动微调时再通过
     * JVM 参数启用。
     */
    private static final long AUDIO_OUTPUT_LATENCY_COMPENSATION_MILLIS =
            TIMING.audioLatencyCompensationMillis();
    private static final long CHASE_WINDOW_MILLIS = TIMING.chaseWindowMillis();
    private static final long SLOWDOWN_WINDOW_MILLIS = TIMING.slowdownWindowMillis();
    private static final long RUNTIME_LAG_RESTART_MILLIS = TIMING.runtimeLagRestartMillis();
    private static final long RUNTIME_LAG_CONFIRM_MILLIS = TIMING.runtimeLagConfirmMillis();
    private static final long RUNTIME_LAG_RESTART_COOLDOWN_MILLIS = TIMING.runtimeLagRestartCooldownMillis();
    private static final long DECODER_RESTART_CLOSE_TIMEOUT_MILLIS = TIMING.decoderRestartCloseTimeoutMillis();
    private static final long FIRST_FRAME_TIMEOUT_MILLIS = TIMING.firstFrameTimeoutMillis();
    private static final int MAX_FIRST_FRAME_RECOVERY_ATTEMPTS = TIMING.firstFrameRecoveryAttempts();
    volatile int targetWidth;
    volatile int targetHeight;
    private final int fps;
    final List<VideoCandidate> candidates;
    /** 直播总线源：pts 已在音频输出时间域，播放时钟不得按"当前媒体位置"重基准。 */
    final boolean liveSource;
    final PlaybackSessionId playbackSessionId;
    private final long startOffsetMillis;
    final long totalMillis;
    final boolean preferNative;
    final String decoderOverride;
    final VideoPlaybackTextures textures;
    final VideoPlaybackAnchor anchor;
    final VideoPlaybackFrameQueue frameQueue = new VideoPlaybackFrameQueue(PRESENTATION.queueCapacity());
    final AtomicLong generation = new AtomicLong();
    final VideoConsumerRegistry<BlockPos> consumers = new VideoConsumerRegistry<>();
    final VideoPhysicalCloseHandoff physicalCloseHandoff = new VideoPhysicalCloseHandoff();
    final VideoPerformanceMonitor performanceMonitor = new VideoPerformanceMonitor();
    private final VideoCandidateDecodeRunner candidateDecodeRunner = new VideoCandidateDecodeRunner(this);
    private final VideoPlaybackCloser closer = new VideoPlaybackCloser(this);
    private final VideoPlaybackPresentation presentation = new VideoPlaybackPresentation(this);
    volatile boolean running;
    volatile boolean hasFrame;
    volatile long startNanoTime;
    volatile long decoderGenerationStartedNanoTime;
    volatile Thread decodeThread;
    volatile AutoCloseable decoder;
    volatile CompletableFuture<Void> decodeExit = CompletableFuture.completedFuture(null);
    volatile boolean firstFrameLogged;
    volatile boolean firstYuvImmediateLogged;
    volatile long firstDecodedNanoTime;
    private volatile boolean startupBufferReady;
    volatile long lastUploadPumpNanoTime;
    volatile long decoderStartOffsetMillis;
    private volatile long lastUploadedPtsNanos = -1L;
    private volatile long lastUploadedBaseOffsetMillis = -1L;
    volatile long adaptiveRestartOffsetMillis = -1L;
    volatile long lastVisibleNanoTime;
    volatile long offscreenSinceNanoTime;
    private volatile long runtimeLagSinceNanoTime;
    private volatile boolean visualSyncSuspended;
    private volatile long lastRuntimeLagRestartNanoTime;
    volatile boolean restartInProgress;
    volatile VideoDecoderRestartState restartState = VideoDecoderRestartState.ACTIVE;
    volatile boolean prewarmVisible = true;
    volatile boolean decodeAdmissionGranted;
    volatile boolean initialDecodeWorkerStarted;
    volatile boolean loggedOffscreenPause;
    volatile boolean networkFailure;
    volatile boolean terminalFailure;
    private volatile boolean networkFailureNotified;
    volatile boolean stopRequested;
    volatile VideoCandidate activeCandidate;
    volatile String actualDecoderBackend = "unknown";
    volatile String fallbackReason = "";
    volatile boolean performanceFallbackLocked;
    private volatile boolean performanceNoH264Logged;
    private volatile int firstFrameRecoveryAttempts;
    private int consecutiveBadUploads;

    VideoPlaybackInstance(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> projectorPositions,
            BlockPos turntablePos, boolean preferNative, String decoderOverride) {
        this(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, totalMillis,
                projectorPositions, VideoPlaybackAnchor.turntable(turntablePos, sessionId, Math.max(0L, totalMillis)),
                preferNative, decoderOverride, List.of(new VideoCandidate(videoUrl, codecId, targetWidth,
                        targetHeight, fps, 0)));
    }

    VideoPlaybackInstance(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> projectorPositions,
            VideoPlaybackAnchor anchor, boolean preferNative, String decoderOverride) {
        this(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, totalMillis,
                projectorPositions, anchor, preferNative, decoderOverride,
                List.of(new VideoCandidate(videoUrl, codecId, targetWidth, targetHeight, fps, 0)));
    }

    VideoPlaybackInstance(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> projectorPositions,
            VideoPlaybackAnchor anchor, boolean preferNative, String decoderOverride, List<VideoCandidate> candidates) {
        this.targetWidth = Math.max(1, targetWidth);
        this.targetHeight = Math.max(1, targetHeight);
        this.fps = Math.max(1, fps);
        this.candidates = candidates == null || candidates.isEmpty()
                ? List.of(new VideoCandidate(videoUrl, codecId, targetWidth, targetHeight, fps, 0))
                : List.copyOf(candidates);
        this.liveSource = com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus
                .isBusUrl(this.candidates.get(0).url());
        this.playbackSessionId = PlaybackSessionId.of(sessionId);
        this.startOffsetMillis = Math.max(0L, startOffsetMillis);
        this.totalMillis = Math.max(0L, totalMillis);
        this.preferNative = preferNative;
        this.decoderOverride = decoderOverride;
        this.anchor = anchor != null ? anchor
                : VideoPlaybackAnchor.turntable(null, sessionId(), this.totalMillis);
        this.textures = new VideoPlaybackTextures(sessionId());
        replaceProjectors(projectorPositions);
        LOGGER.debug("视频会话创建: session={}, {}x{} @ {}fps, renderBackend={}, decodeFormat={}", sessionId(),
                this.targetWidth, this.targetHeight, this.fps, VideoBillboardPreview.RENDER_BACKEND,
                VideoBillboardPreview.YUV_DECODE_BACKEND
                        ? (VideoBillboardPreview.isCustomYuvShaderAvailable()
                                ? VideoBillboardPreview.yuvDecodeFormat().name() + "→RGB(shader)"
                                : VideoBillboardPreview.yuvDecodeFormat().name() + "→RGBA(cpu/iris-fallback)")
                        : "RGBA");
    }

    synchronized void start() {
        if (stopRequested) {
            return;
        }
        running = true;
        hasFrame = false;
        firstFrameLogged = false;
        firstYuvImmediateLogged = false;
        firstDecodedNanoTime = 0L;
        startupBufferReady = false;
        lastUploadPumpNanoTime = System.nanoTime();
        decoderStartOffsetMillis = startOffsetMillis;
        lastUploadedPtsNanos = -1L;
        lastUploadedBaseOffsetMillis = -1L;
        adaptiveRestartOffsetMillis = -1L;
        boolean immediatelyVisible = consumers.hasGuiConsumer() || hasHolographicTurntableConsumer();
        lastVisibleNanoTime = immediatelyVisible ? System.nanoTime() : 0L;
        offscreenSinceNanoTime = 0L;
        prewarmVisible = immediatelyVisible;
        decodeAdmissionGranted = immediatelyVisible;
        initialDecodeWorkerStarted = false;
        loggedOffscreenPause = false;
        visualSyncSuspended = !immediatelyVisible;
        networkFailure = false;
        terminalFailure = false;
        networkFailureNotified = false;
        firstFrameRecoveryAttempts = 0;
        activeCandidate = null;
        actualDecoderBackend = "unknown";
        fallbackReason = candidates.stream().noneMatch(candidate -> candidate.codecId() == 13)
                && candidates.stream().anyMatch(candidate -> candidate.codecId() == 7)
                        ? VideoFallbackReason.NO_AV1_STREAM : "";
        performanceFallbackLocked = false;
        performanceNoH264Logged = false;
        consecutiveBadUploads = 0;
        frameQueue.clear();
        startNanoTime = immediatelyVisible ? System.nanoTime() : 0L;
        decoderGenerationStartedNanoTime = startNanoTime;
        generation.incrementAndGet();
        if (immediatelyVisible) {
            grantDecodeAdmission();
        }
    }

    synchronized void grantDecodeAdmission() {
        if (!running || stopRequested) {
            return;
        }
        if (!decodeAdmissionGranted) {
            long now = System.nanoTime();
            decodeAdmissionGranted = true;
            startNanoTime = now;
            decoderGenerationStartedNanoTime = now;
        }
        if (!initialDecodeWorkerStarted) {
            initialDecodeWorkerStarted = true;
            long gen = generation.get();
            candidateDecodeRunner.start(gen, "bili-video-" + sessionId());
        }
    }

    void resetFirstFrameWatchdogAfterVisibilityResume(long nowNanoTime) {
        if (decodeAdmissionGranted && !hasFrame) {
            startNanoTime = nowNanoTime;
            decoderGenerationStartedNanoTime = nowNanoTime;
        }
    }

    long playbackNanos() {
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

    long effectiveDecoderStartOffsetMillis() {
        if (liveSource) {
            // 直播样本 pts 与播放时钟同域（音频输出时间），重基准会把帧推到"未来"。
            return 0L;
        }
        long restartOffset = adaptiveRestartOffsetMillis;
        long requestedOffsetMillis = restartOffset >= 0L ? restartOffset : startOffsetMillis;
        long synced = anchor.timeline().mediaMillis();
        long offset = synced >= 0L ? Math.max(requestedOffsetMillis, synced) : requestedOffsetMillis;
        return totalMillis > 0L ? Math.min(totalMillis, offset) : offset;
    }

    void pumpUploadOnRenderThread() {
        lastUploadPumpNanoTime = System.nanoTime();
        if (!visualSyncActive()) {
            suspendVisualSync();
            return;
        }
        visualSyncSuspended = false;
        if (!running && frameQueue.isEmpty()) {
            return;
        }
        maybeRestartForRuntimeLag();
        if (!startupBufferReady && shouldWaitForStartupBuffer()) {
            return;
        }
        startupBufferReady = true;
        long playbackNs = playbackNanos();
        DecodedVideoFrame frame = frameQueue.pollBestFrame(playbackNs,
                VideoPipelineProperties.earlyToleranceMillis() * 1_000_000L);
        performanceMonitor.recordDroppedFrames(frameQueue.drainDroppedFrames());
        if (frame == null) {
            if (firstFrameLogged && frameQueue.isEmpty()) {
                performanceMonitor.recordStarvation();
            }
            observeSustainedPerformance();
            return;
        }
        long maxVisibleLagNs = VideoPipelineProperties.maxVisibleLagMillis() * 1_000_000L;
        if (maxVisibleLagNs > 0L && playbackNs - frame.ptsNanos() > maxVisibleLagNs) {
            DecodedVideoFrame chase = frameQueue.pollBestFrame(playbackNs, 0L);
            if (chase != null) {
                frame.close();
                performanceMonitor.recordDroppedFrames(1L);
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
                lastUploadedBaseOffsetMillis = Math.max(startOffsetMillis, decoderStartOffsetMillis);
                lastUploadedPtsNanos = frame.ptsNanos();
            }
        } catch (OutOfMemoryError error) {
            com.zhongbai233.net_music_can_play_bili.client.ClientMediaLifecycleHandler
                    .tripMemoryProtection("video texture allocation failed: " + error.getMessage());
            LOGGER.error("视频纹理内存分配失败并触发熔断: session={}", sessionId(), error);
        } finally {
            frame.close();
        }
        if (uploaded) {
            recordAdaptiveUploadCost(uploadNs);
        }
        observeSustainedPerformance();
    }

    private void observeSustainedPerformance() {
        VideoCandidate candidate = activeCandidate;
        if (candidate == null || !performanceMonitor.started()) {
            return;
        }
        long masterMillis = anchor.timeline().mediaMillis();
        long displayedMillis = mediaMillis();
        long queuedMillis = queuedMediaMillis();
        long bestVideoMillis = Math.max(displayedMillis, queuedMillis);
        if (masterMillis >= 0L && bestVideoMillis >= 0L) {
            performanceMonitor.recordSyncDriftMillis(masterMillis - bestVideoMillis);
        }
        long now = System.nanoTime();
        performanceMonitor.sampleNativeResources(now);
        VideoPerformanceFallbackPolicy.Snapshot snapshot = performanceMonitor.snapshot(now);
        boolean h264Available = candidates.stream().anyMatch(next -> next.codecId() == 7);
        VideoPerformanceFallbackPolicy.Decision decision = VideoPerformanceFallbackPolicy.decide(
                snapshot, candidate.codecId() == 13 && !liveSource, h264Available, performanceFallbackLocked);
        if (decision.shouldFallback()) {
            requestSustainedPerformanceFallback(decision, snapshot);
        } else if (decision == VideoPerformanceFallbackPolicy.Decision.KEEP_NO_H264
                && !performanceNoH264Logged) {
            performanceNoH264Logged = true;
            fallbackReason = decision.reason();
            LOGGER.warn("AV1 持续性能超预算但同次 playurl 无 H.264 候选，保持当前后端: session={}, backend={}",
                    sessionId(), actualDecoderBackend);
        }
    }

    private synchronized void requestSustainedPerformanceFallback(
            VideoPerformanceFallbackPolicy.Decision decision,
            VideoPerformanceFallbackPolicy.Snapshot snapshot) {
        if (performanceFallbackLocked || restartInProgress || stopRequested || !running
                || activeCandidate == null || activeCandidate.codecId() != 13
                || candidates.stream().noneMatch(candidate -> candidate.codecId() == 7)) {
            return;
        }
        performanceFallbackLocked = true;
        fallbackReason = decision.reason();
        long offsetMillis = currentRestartOffsetMillis();
        LOGGER.warn(
                "AV1 持续性能回退并锁定 H.264: session={}, reason={}, backend={}, actualFps={}/{}, avg={}ms, p95={}ms, starvation={}, dropped={} ({}), drift={}ms, driftGrowth={}ms, nativePeak={} bytes, surfaces={}, offset={}ms",
                sessionId(), fallbackReason, snapshot.backend(),
                String.format(java.util.Locale.ROOT, "%.2f", snapshot.actualDecodeFps()), snapshot.targetFps(),
                String.format(java.util.Locale.ROOT, "%.2f", snapshot.averageDecodeMillis()),
                String.format(java.util.Locale.ROOT, "%.2f", snapshot.p95DecodeMillis()),
                snapshot.starvationCount(), snapshot.droppedFrames(),
                String.format(java.util.Locale.ROOT, "%.2f%%", snapshot.droppedFrameRatio() * 100.0D),
                snapshot.latestSyncDriftMillis(), snapshot.syncDriftGrowthMillis(),
                snapshot.nativeFrameBytesPeak(), snapshot.nativeSurfacePeak(), offsetMillis);
        restartDecoder(targetWidth, targetHeight, offsetMillis, true);
    }

    private void maybeRestartForRuntimeLag() {
        if (!visualSyncActive() || !presentation.restartAllowed() || !hasFrame
                || !hasVideoConsumer() || RUNTIME_LAG_RESTART_MILLIS <= 0L) {
            runtimeLagSinceNanoTime = 0L;
            return;
        }
        long masterMillis = anchor.timeline().mediaMillis();
        if (masterMillis < 0L) {
            return;
        }
        long displayedMillis = mediaMillis();
        long queuedMillis = queuedMediaMillis();
        long bestVideoMillis = Math.max(displayedMillis, queuedMillis);
        long lagMillis = bestVideoMillis >= 0L ? masterMillis - bestVideoMillis : 0L;
        long now = System.nanoTime();
        if (lagMillis < RUNTIME_LAG_RESTART_MILLIS) {
            runtimeLagSinceNanoTime = 0L;
            return;
        }
        if (runtimeLagSinceNanoTime == 0L) {
            runtimeLagSinceNanoTime = now;
            return;
        }
        if (RUNTIME_LAG_CONFIRM_MILLIS > 0L
                && now - runtimeLagSinceNanoTime < RUNTIME_LAG_CONFIRM_MILLIS * 1_000_000L) {
            return;
        }
        if (lastRuntimeLagRestartNanoTime != 0L
                && now - lastRuntimeLagRestartNanoTime < RUNTIME_LAG_RESTART_COOLDOWN_MILLIS * 1_000_000L) {
            return;
        }
        long restartOffsetMillis = totalMillis > 0L ? Math.min(totalMillis, masterMillis) : masterMillis;
        LOGGER.debug("视频运行期持续落后，主动重定位: session={}, lag={}ms, master={}ms, video={}ms, offset={}ms",
                sessionId(), lagMillis, masterMillis, bestVideoMillis, restartOffsetMillis);
        lastRuntimeLagRestartNanoTime = now;
        runtimeLagSinceNanoTime = 0L;
        restartDecoder(targetWidth, targetHeight, restartOffsetMillis, true);
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
                sessionId(), currentWidth, currentHeight, nextWidth, nextHeight, restartOffsetMillis);
        restartDecoder(nextWidth, nextHeight, restartOffsetMillis);
        return true;
    }

    private long currentRestartOffsetMillis() {
        if (liveSource) {
            // 直播重启后从总线关键帧续播，不存在 seek 偏移。
            return 0L;
        }
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

    void restartDecoder(int nextWidth, int nextHeight, long restartOffsetMillis, boolean keepVisibleFrame) {
        if (restartInProgress) {
            LOGGER.debug("视频解码器重启正在等待旧 worker 退出，忽略重复请求: session={}", sessionId());
            return;
        }
        restartInProgress = true;
        restartState = VideoDecoderRestartState.CLOSING;
        long gen = generation.incrementAndGet();
        boolean preserveVisibleFrame = keepVisibleFrame
                && nextWidth == targetWidth
                && nextHeight == targetHeight
                && hasFrame
                && (textures.hasRgbaTexture() || textures.hasYuvTexture());
        AutoCloseable oldDecoder = decoder;
        decoder = null;
        CompletableFuture<Void> oldDecodeExit = decodeExit;
        CompletableFuture<Void> nativeTermination = CompletableFuture.completedFuture(null);
        if (oldDecoder instanceof com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder nativeDecoder) {
            nativeDecoder.requestClose();
            nativeTermination = nativeDecoder.terminationFuture();
        }
        CompletableFuture<Void> capturedNativeTermination = nativeTermination;
        Thread oldThread = decodeThread;
        if (oldThread != null) {
            oldThread.interrupt();
        }
        CompletableFuture<Void> closeCompletion = MediaCloseExecutor.closeAsyncStrict(oldDecoder,
                "adaptive video decoder " + sessionId());
        physicalCloseHandoff.attachClose(closeCompletion, nativeTermination, oldDecodeExit);

        frameQueue.clear();
        if (!preserveVisibleFrame) {
            textures.release();
        }
        targetWidth = nextWidth;
        targetHeight = nextHeight;
        hasFrame = preserveVisibleFrame;
        firstFrameLogged = false;
        firstDecodedNanoTime = 0L;
        startupBufferReady = false;
        networkFailure = false;
        terminalFailure = false;
        networkFailureNotified = false;
        startNanoTime = System.nanoTime();
        lastUploadPumpNanoTime = System.nanoTime();
        decoderStartOffsetMillis = Math.max(0L, restartOffsetMillis);
        adaptiveRestartOffsetMillis = decoderStartOffsetMillis;
        if (!preserveVisibleFrame) {
            lastUploadedPtsNanos = -1L;
            lastUploadedBaseOffsetMillis = -1L;
        }
        consecutiveBadUploads = 0;
        // Keep the session active while the old native worker drains so periodic sync
        // packets cannot
        // replace this instance. The generation guard prevents the old worker from
        // publishing state.
        running = true;
        CompletableFuture<Void> closeBarrier = CompletableFuture
            .allOf(closeCompletion, capturedNativeTermination, oldDecodeExit);
        CompletableFuture.delayedExecutor(Math.max(1L, DECODER_RESTART_CLOSE_TIMEOUT_MILLIS),
                TimeUnit.MILLISECONDS).execute(() -> {
                    if (!closeBarrier.isDone() && gen == generation.get() && running && !stopRequested) {
                        failRestartClose(gen, closeCompletion, capturedNativeTermination, oldDecodeExit);
                    }
                });
        closeBarrier.whenComplete((ignored, error) -> {
            if (error == null) {
                completeRestartClose(gen, preserveVisibleFrame);
            } else {
                failRestartClose(gen, closeCompletion, capturedNativeTermination, oldDecodeExit);
            }
        });
    }

    private synchronized void completeRestartClose(long gen, boolean preserveVisibleFrame) {
        if (restartState != VideoDecoderRestartState.CLOSING || gen != generation.get()
                || !running || stopRequested || !hasVideoConsumer()) {
            if (gen == generation.get() && restartState == VideoDecoderRestartState.CLOSING) {
                restartInProgress = false;
                restartState = stopRequested ? VideoDecoderRestartState.STOPPED : VideoDecoderRestartState.ACTIVE;
            }
            return;
        }
        restartInProgress = false;
        restartState = VideoDecoderRestartState.ACTIVE;
        decoderGenerationStartedNanoTime = System.nanoTime();
        candidateDecodeRunner.start(gen, preserveVisibleFrame
                ? "bili-video-" + sessionId() + "-resume"
                : "bili-video-" + sessionId() + "-adaptive");
    }

    private synchronized void failRestartClose(long gen, CompletableFuture<Void> closeCompletion,
            CompletableFuture<Void> nativeTermination, CompletableFuture<Void> oldDecodeExit) {
        if (restartState != VideoDecoderRestartState.CLOSING || gen != generation.get()
                || stopRequested || !running) {
            return;
        }
        generation.incrementAndGet();
        restartInProgress = false;
        restartState = VideoDecoderRestartState.FAILED_CLOSE;
        networkFailure = false;
        terminalFailure = true;
        frameQueue.clear();
        VideoZombieCloseSupervisor.global().track(sessionId(), gen, closeCompletion, nativeTermination, oldDecodeExit);
        LOGGER.error("旧视频解码器关闭超时，会话进入 FAILED_CLOSE，禁止迟到 generation 复活: session={}, timeout={}ms",
                sessionId(), DECODER_RESTART_CLOSE_TIMEOUT_MILLIS);
    }

    private boolean shouldWaitForStartupBuffer() {
        int requiredFrames = VideoPipelineProperties.startupPrebufferFrames();
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
        long maxWaitNs = VideoPipelineProperties.startupPrebufferMaxWaitMillis() * 1_000_000L;
        boolean wait = System.nanoTime() - firstDecodedNs < maxWaitNs;
        if (!wait) {
            return false;
        }
        return wait;
    }

    private boolean uploadDecodedFrameOnRenderThread(VideoBillboardPreview.DecodedFrame frame) {
        boolean uploaded = textures.uploadDecodedFrame(frame, targetWidth, targetHeight);
        hasFrame |= uploaded;
        return uploaded;
    }

    VideoBillboardPreview.ProjectorFrameSnapshot frameSnapshot(BlockPos projectorPos) {
        return presentation.frameSnapshot(projectorPos);
    }

    VideoBillboardPreview.ProjectorFrameSnapshot displayFrameSnapshot(BlockPos projectorPos) {
        return presentation.displayFrameSnapshot(projectorPos);
    }

    VideoBillboardPreview.ProjectorFrameSnapshot realFrameSnapshot(BlockPos projectorPos) {
        return presentation.realFrameSnapshot(projectorPos);
    }

    VideoBillboardPreview.ProjectorFrameSnapshot failurePlaceholderSnapshot() {
        return presentation.failurePlaceholderSnapshot();
    }

    static VideoBillboardPreview.ProjectorFrameSnapshot loadingPlaceholderSnapshot(long startedNanoTime) {
        return VideoPlaceholderFrames.loading(startedNanoTime);
    }

    static VideoBillboardPreview.ProjectorFrameSnapshot idlePlaceholderSnapshot() {
        return VideoPlaceholderFrames.idle();
    }

    VideoBillboardPreview.ProjectorFrameSnapshot turntableFrameSnapshot(BlockPos turntablePos) {
        return presentation.turntableFrameSnapshot(turntablePos);
    }

    VideoBillboardPreview.ProjectorFrameSnapshot previewFrameSnapshot() {
        return presentation.previewFrameSnapshot();
    }

    void submit(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera) {
        presentation.submit(event, minecraft, camera);
    }

    void renderYuvImmediate(RenderLevelStageEvent event, String route) {
        presentation.renderYuvImmediate(event, route);
    }

    boolean isWithinAudioRange(Minecraft minecraft) {
        return presentation.isWithinAudioRange(minecraft);
    }

    boolean isRunningAtOffset(long requestedOffsetMillis) {
        return isRunningAtOffset(requestedOffsetMillis, 1_500L);
    }

    boolean isRunningAtOffset(long requestedOffsetMillis, long toleranceMillis) {
        if (!running) {
            return false;
        }
        if (restartState.pinsRegistryEntry()) {
            return true;
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
        if (restartState.pinsRegistryEntry()) {
            // The old generation still owns (or may still own) native state.
            // Keep the registry entry pinned so a sync/reseek cannot replace it
            // with a second decoder while physical convergence is pending.
            return true;
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

    synchronized boolean requestSyncedReseek(long requestedOffsetMillis) {
        if (!running || stopRequested || terminalFailure || networkFailure
                || restartState != VideoDecoderRestartState.ACTIVE || restartInProgress
                || !hasVideoConsumer()) {
            return false;
        }
        long target = totalMillis > 0L
                ? Math.min(totalMillis, Math.max(0L, requestedOffsetMillis))
                : Math.max(0L, requestedOffsetMillis);
        restartDecoder(targetWidth, targetHeight, target, hasFrame);
        return true;
    }

    Object replacementOwnerKey() {
        return anchor.replacementOwnerKey();
    }

    ProjectionReplacementGate.CloseHandoff closeHandoff() {
        return physicalCloseHandoff.snapshot();
    }

    void replaceProjectors(Collection<BlockPos> positions) {
        consumers.replaceProjectors(VideoBillboardPreview.immutablePositions(positions));
    }

    List<BlockPos> projectorPositions() {
        return consumers.projectors();
    }

    void addProjector(BlockPos pos) {
        if (pos != null) {
            consumers.addProjector(pos.immutable());
        }
    }

    void removeProjector(BlockPos pos) {
        consumers.removeProjector(pos);
    }

    boolean isForTurntable(BlockPos pos) {
        return anchor.isForTurntable(pos);
    }

    boolean isSession(String candidateSessionId) {
        return sessionId().equals(candidateSessionId != null ? candidateSessionId : "");
    }

    boolean hasProjector(BlockPos pos) {
        return consumers.containsProjector(pos);
    }

    String sessionId() {
        return playbackSessionId.value();
    }

    PlaybackSessionId playbackSessionId() {
        return playbackSessionId;
    }

    long startNanoTime() {
        return startNanoTime;
    }

    boolean hasProjectors() {
        return consumers.hasProjectors();
    }

    int projectorCount() {
        return consumers.projectorCount();
    }

    boolean hasVideoConsumer() {
        return consumers.hasDirectConsumer() || hasHolographicTurntableConsumer();
    }

    void setGuiConsumer(boolean value) {
        consumers.setGuiConsumer(value);
        if (value) {
            prewarmVisible = true;
            offscreenSinceNanoTime = 0L;
            lastVisibleNanoTime = System.nanoTime();
            grantDecodeAdmission();
        }
    }

    boolean hasGuiConsumer() {
        return consumers.hasGuiConsumer();
    }

    boolean hasHolographicTurntableConsumer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientDisplayProperties.holographicWorldScreenEnabled()
                || minecraft == null || minecraft.level == null) {
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
        return consumers.containsProjector(pos);
    }

    boolean isRunning() {
        return running && restartState != VideoDecoderRestartState.FAILED_CLOSE;
    }

    boolean hasFrame() {
        return hasFrame;
    }

    /**
     * 周期同步调用的首帧 watchdog。重启始终复用实例内的关闭屏障，避免旧 native worker
     * 尚未退出时创建第二个实例；重试耗尽后保留 session 引用并显示明确错误画面。
     */
    synchronized boolean ensureFirstFrameProgress() {
        if (!running || hasFrame || terminalFailure) {
            return false;
        }
        if (!decodeAdmissionGranted || !prewarmVisible) {
            // A dormant invisible session is not a stalled decoder and must not trip the
            // first-frame watchdog. Its decoder has not been opened yet.
            return true;
        }
        long waitingMillis = startNanoTime > 0L
                ? Math.max(0L, (System.nanoTime() - startNanoTime) / 1_000_000L) : 0L;
        VideoFirstFrameRecoveryPolicy.Decision decision = VideoFirstFrameRecoveryPolicy.decide(
                running, hasFrame, restartInProgress, waitingMillis, FIRST_FRAME_TIMEOUT_MILLIS,
                firstFrameRecoveryAttempts, MAX_FIRST_FRAME_RECOVERY_ATTEMPTS);
        if (decision == VideoFirstFrameRecoveryPolicy.Decision.RESTART) {
            firstFrameRecoveryAttempts++;
            long restartOffsetMillis = currentRestartOffsetMillis();
            LOGGER.warn("视频首帧等待超时，执行受控恢复: session={}, wait={}ms, attempt={}/{}, offset={}ms",
                    sessionId(), waitingMillis, firstFrameRecoveryAttempts, MAX_FIRST_FRAME_RECOVERY_ATTEMPTS,
                    restartOffsetMillis);
            restartDecoder(targetWidth, targetHeight, restartOffsetMillis, false);
        } else if (decision == VideoFirstFrameRecoveryPolicy.Decision.FAIL) {
            failStalledStartup(waitingMillis);
        }
        return true;
    }

    private void failStalledStartup(long waitingMillis) {
        long gen = generation.incrementAndGet();
        AutoCloseable stalledDecoder = decoder;
        decoder = null;
        Thread stalledThread = decodeThread;
        if (stalledThread != null) {
            stalledThread.interrupt();
        }
        frameQueue.clear();
        restartInProgress = false;
        networkFailure = false;
        terminalFailure = true;
        // 保留 running 标记，使共享 session 不会在本 tick 被跨实例替换；下次同步会命中
        // terminalFailure，并稳定显示 ERROR，等待新 session 或显式刷新。
        running = true;
        if (stalledDecoder instanceof com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder nativeDecoder) {
            nativeDecoder.requestClose();
        }
        MediaCloseExecutor.closeAsync(stalledDecoder, "stalled startup video decoder " + sessionId());
        LOGGER.error("视频首帧恢复次数耗尽，结束永久 Loading: session={}, generation={}, wait={}ms, attempts={}",
                sessionId(), gen, waitingMillis, firstFrameRecoveryAttempts);
    }

    boolean hasNetworkFailure() {
        return networkFailure;
    }

    boolean hasTerminalFailure() {
        return terminalFailure || restartState.isTerminalFailure();
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
        LOGGER.info("手动重试视频网络连接: session={}, offset={}ms", sessionId(), retryOffsetMillis);
        restartDecoder(targetWidth, targetHeight, retryOffsetMillis, hasFrame);
        return true;
    }

    void notifyNetworkFailure() {
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
        return hasFrame ? VideoMediaTimestampPolicy.absoluteMillis(lastUploadedBaseOffsetMillis, uploadedPts,
                AUDIO_OUTPUT_LATENCY_COMPENSATION_MILLIS, totalMillis) : -1L;
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

    boolean visualSyncActive() {
        return VideoVisualSyncPolicy.active(running, terminalFailure, prewarmVisible,
                candidateDecodeRunner.isOffscreenPauseActive());
    }

    private void suspendVisualSync() {
        runtimeLagSinceNanoTime = 0L;
        if (!visualSyncSuspended) {
            visualSyncSuspended = true;
            performanceMonitor.resetSyncDriftWindow();
        }
    }

    boolean visualSyncActiveForBench() {
        return visualSyncActive();
    }

    long generationForBench() {
        return generation.get();
    }

    long decoderStartOffsetMillisForBench() {
        return decoderStartOffsetMillis;
    }

    String restartStateForBench() {
        return restartState.name();
    }

    boolean prewarmVisibleForBench() {
        return prewarmVisible;
    }

    boolean offscreenPauseActiveForBench() {
        return candidateDecodeRunner.isOffscreenPauseActive();
    }

    VideoBillboardPreview.VideoStatus status() {
        VideoCandidate candidate = activeCandidate;
        int requestedQuality = candidates.stream().mapToInt(option -> option.quality()).max().orElse(0);
        return new VideoBillboardPreview.VideoStatus(targetWidth, targetHeight,
                candidate != null ? candidate.fps() : fps, hasFrame, true,
                requestedQuality, candidate != null ? candidate.quality() : 0,
                candidate != null ? candidate.codecId() : 0, actualDecoderBackend, fallbackReason);
    }

    synchronized void stop() {
        closer.stop();
    }

    synchronized void abandonBeforeStart() {
        closer.abandonBeforeStart();
    }

}
