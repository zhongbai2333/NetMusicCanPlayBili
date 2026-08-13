package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.VideoCandidate;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.media.stream.MediaNetworkFailureClassifier;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.EnumSet;

/**
 * 单个同步视频播放会话，负责解码线程、会话专属动态纹理和投影仪列表
 */
final class VideoPlaybackInstance {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final VideoPipelineProperties.Timing TIMING = VideoPipelineProperties.timing();
    private static final VideoPipelineProperties.Offscreen OFFSCREEN = VideoPipelineProperties.offscreen();
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
    private static final long DECODER_STABILIZATION_MILLIS = TIMING.decoderStabilizationMillis();
    private static final long DECODER_RESTART_CLOSE_TIMEOUT_MILLIS = TIMING.decoderRestartCloseTimeoutMillis();
    private static final long FIRST_FRAME_TIMEOUT_MILLIS = TIMING.firstFrameTimeoutMillis();
    private static final int MAX_FIRST_FRAME_RECOVERY_ATTEMPTS = TIMING.firstFrameRecoveryAttempts();
    private static final boolean OFFSCREEN_PAUSE_DECODE = OFFSCREEN.pauseDecode();
    private static final long OFFSCREEN_GRACE_NANOS = OFFSCREEN.graceMillis() * 1_000_000L;
    private static final long OFFSCREEN_RESUME_RESTART_LAG_NANOS = OFFSCREEN.resumeRestartLagMillis() * 1_000_000L;
    private static final double OFFSCREEN_PREWARM_DOT_THRESHOLD = OFFSCREEN.prewarmDotThreshold();
    private static final int MAX_OPERATIONAL_SOURCE_WIDTH = PRESENTATION.maxSourceWidth();
    private static final int MAX_OPERATIONAL_SOURCE_HEIGHT = PRESENTATION.maxSourceHeight();
    private static final double IRIS_WARNING_PLACEHOLDER_VIEW_DEPTH_OFFSET =
            PRESENTATION.irisWarningViewDepthOffset();
    private static final float IRIS_WARNING_PLACEHOLDER_LOCAL_DEPTH_OFFSET =
            PRESENTATION.irisWarningLocalDepthOffset();
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
        private static final Identifier IDLE_PLACEHOLDER_TEXTURE = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/video_loading/idle_base.png");
        private static final boolean NETWORK_ERROR_PLACEHOLDER_ENABLED =
            VideoPipelineProperties.networkErrorPlaceholderEnabled();
    private static final int LOADING_PLACEHOLDER_WIDTH = 320;
    private static final int LOADING_PLACEHOLDER_HEIGHT = 180;

    private volatile int targetWidth;
    private volatile int targetHeight;
    private final int fps;
    private final List<VideoCandidate> candidates;
    /** 直播总线源：pts 已在音频输出时间域，播放时钟不得按"当前媒体位置"重基准。 */
    private final boolean liveSource;
    private final PlaybackSessionId playbackSessionId;
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
    private final VideoFrameQueue frameQueue = new VideoFrameQueue(PRESENTATION.queueCapacity());
    private final AtomicLong generation = new AtomicLong();
    private final VideoConsumerRegistry<BlockPos> consumers = new VideoConsumerRegistry<>();
    private final VideoPhysicalCloseHandoff physicalCloseHandoff = new VideoPhysicalCloseHandoff();
    private final VideoPerformanceMonitor performanceMonitor = new VideoPerformanceMonitor();
    private volatile boolean running;
    private volatile boolean hasFrame;
    private volatile long startNanoTime;
    private volatile long decoderGenerationStartedNanoTime;
    private volatile Thread decodeThread;
    private volatile AutoCloseable decoder;
    private volatile CompletableFuture<Void> decodeExit = CompletableFuture.completedFuture(null);
    private volatile DynamicTexture frontTexture;
    private volatile DynamicTexture backTexture;
    private volatile VideoYuvTextureSet yuvTextureSet;
    private volatile Identifier frontTextureId;
    private volatile Identifier backTextureId;
    private volatile boolean firstFrameLogged;
    private volatile boolean firstYuvImmediateLogged;
    private volatile long firstDecodedNanoTime;
    private volatile boolean startupBufferReady;
    private volatile long lastUploadPumpNanoTime;
    private volatile long decoderStartOffsetMillis;
    private volatile long lastUploadedPtsNanos = -1L;
    private volatile long lastUploadedBaseOffsetMillis = -1L;
    private volatile long adaptiveRestartOffsetMillis = -1L;
    private volatile long lastVisibleNanoTime;
    private volatile long offscreenSinceNanoTime;
    private volatile long runtimeLagSinceNanoTime;
    private volatile long lastRuntimeLagRestartNanoTime;
    private volatile boolean restartInProgress;
    private volatile VideoDecoderRestartState restartState = VideoDecoderRestartState.ACTIVE;
    private volatile boolean prewarmVisible = true;
    private volatile boolean loggedOffscreenPause;
    private volatile boolean networkFailure;
    private volatile boolean terminalFailure;
    private volatile boolean networkFailureNotified;
    private volatile boolean stopRequested;
    private volatile VideoCandidate activeCandidate;
    private volatile String actualDecoderBackend = "unknown";
    private volatile String fallbackReason = "";
    private volatile boolean performanceFallbackLocked;
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
        String textureSuffix = Integer.toUnsignedString(sessionId().hashCode(), 16);
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
        lastVisibleNanoTime = System.nanoTime();
        offscreenSinceNanoTime = 0L;
        prewarmVisible = true;
        loggedOffscreenPause = false;
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
        startNanoTime = System.nanoTime();
        decoderGenerationStartedNanoTime = startNanoTime;
        long gen = generation.incrementAndGet();
        startDecodeThread(gen, "bili-video-" + sessionId());
    }

    private void startDecodeThread(long gen, String threadName) {
        CompletableFuture<Void> exit = new CompletableFuture<>();
        decodeExit = exit;
        physicalCloseHandoff.beginDecode(exit);
        Thread thread = new Thread(() -> {
            try {
                decode(gen);
            } finally {
                exit.complete(null);
            }
        }, threadName);
        thread.setDaemon(true);
        decodeThread = thread;
        try {
            thread.start();
        } catch (RuntimeException | Error startFailure) {
            decodeThread = null;
            exit.completeExceptionally(startFailure);
            throw startFailure;
        }
    }

    private void decode(long gen) {
        Exception lastStartupFailure = null;
        List<VideoCandidate> operationalCandidates = VideoStartupFallbackPolicy.operationalCandidates(candidates,
                MAX_OPERATIONAL_SOURCE_WIDTH, MAX_OPERATIONAL_SOURCE_HEIGHT);
        if (performanceFallbackLocked) {
            operationalCandidates = VideoStartupFallbackPolicy.lockedH264Candidates(operationalCandidates);
        }
        for (VideoCandidate candidate : operationalCandidates) {
            if (!running || gen != generation.get()) {
                return;
            }
            try {
                if (decodeCandidate(gen, candidate)) {
                    return;
                }
            } catch (Exception error) {
                if (gen != generation.get() || !running || isInterruptedWait(error)) {
                    return;
                }
                if (error instanceof VideoBillboardPreview.CandidateResourceCloseException closeFailure) {
                    failCandidateClose(gen, new CandidateCloseTimeoutException(candidate,
                            closeFailure.nativeTermination, closeFailure.getMessage(), closeFailure));
                    return;
                }
                if (error instanceof CandidateCloseTimeoutException closeTimeout) {
                    failCandidateClose(gen, closeTimeout);
                    return;
                }
                if (firstFrameLogged) {
                    handleDecodeFailure(gen, error);
                    return;
                }
                lastStartupFailure = error;
                if (candidate.codecId() == 13) {
                    fallbackReason = VideoFallbackReason.classifyAv1StartupFailure(error,
                            operationalCandidates.stream().anyMatch(next -> next.codecId() == 7));
                }
                LOGGER.warn("视频实例候选首帧失败，尝试下一候选: session={} quality={} codec={} source={}x{} reason={}",
                        sessionId(), candidate.quality(), candidate.codecId(), candidate.sourceWidth(),
                        candidate.sourceHeight(), error.toString());
            }
        }
        if (lastStartupFailure != null) {
            handleDecodeFailure(gen, lastStartupFailure);
        }
        if (gen == generation.get()) {
            running = false;
            decoder = null;
        }
    }

    private boolean decodeCandidate(long gen, VideoCandidate candidate) throws Exception {
        int candidateFps = Math.max(1, candidate.fps());
        long frameIntervalNs = Math.max(1L, 1_000_000_000L / candidateFps);
        long frameIndex = 0L;
        long effectiveStartOffsetMillis = effectiveDecoderStartOffsetMillis();
        decoderStartOffsetMillis = effectiveStartOffsetMillis;
        adaptiveRestartOffsetMillis = -1L;
        boolean candidateCommitted = false;
        VideoStartupFallbackPolicy.DecodeSize decodeSize = VideoStartupFallbackPolicy.candidateDecodeSize(
                targetWidth, targetHeight, candidate.sourceWidth(), candidate.sourceHeight());
        AutoCloseable dec = VideoBillboardPreview.openDecoder(candidate.url(), decodeSize.width(),
                decodeSize.height(),
                candidateFps,
                candidate.codecId(),
                preferNative, decoderOverride, effectiveStartOffsetMillis, totalMillis, consumers.hasGuiConsumer(),
                candidate.decodeMode());
        if (dec instanceof com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder nativeDecoder) {
            physicalCloseHandoff.attachDecoder(nativeDecoder.terminationFuture());
        }
        try {
            decoder = dec;
            while (running && gen == generation.get()) {
                if (!waitWhilePaused(gen)) {
                    break;
                }
                if (!hasVideoConsumer()) {
                    break;
                }
                if (!waitWhileOffscreen(gen, candidateCommitted)) {
                    break;
                }
                if (frameIndex > 0L) {
                    waitForDecodeLead(frameIntervalNs, gen);
                }
                long waitStartNs = System.nanoTime();
                boolean boundedAv1Probe = !candidateCommitted
                        && VideoStartupFallbackPolicy.requiresBoundedFirstFrameProbe(candidate);
                VideoBillboardPreview.DecodedFrame frame = boundedAv1Probe
                        ? VideoBillboardPreview.nextDecodedFrameWithAv1FirstFrameProbe(dec)
                        : VideoBillboardPreview.nextDecodedFrame(dec);
                long waitNs = System.nanoTime() - waitStartNs;
                if (frame == null) {
                    if (!firstFrameLogged) {
                        throw new java.io.IOException("候选在输出首帧前结束");
                    }
                    return true;
                }
                frameIndex++;
                long ptsNanos = frame.ptsNanos() >= 0L ? frame.ptsNanos() : frameIndex * frameIntervalNs;
                if (!firstFrameLogged && shouldDropStaleStartupFrame(ptsNanos)) {
                    try {
                        if (boundedAv1Probe) {
                            VideoBillboardPreview.rejectAv1FirstFrameProbeFrame(dec, frame);
                        }
                    } finally {
                        frame.close();
                    }
                    continue;
                }
                boolean offered;
                try {
                    offered = frameQueue.offer(new DecodedVideoFrame(frameIndex, ptsNanos, frame),
                            () -> running && gen == generation.get());
                } catch (InterruptedException error) {
                    try {
                        if (boundedAv1Probe) {
                            VideoBillboardPreview.rejectAv1FirstFrameProbeFrame(dec, frame);
                        }
                    } finally {
                        frame.close();
                    }
                    throw error;
                }
                if (!offered) {
                    try {
                        if (boundedAv1Probe) {
                            VideoBillboardPreview.rejectAv1FirstFrameProbeFrame(dec, frame);
                        }
                    } finally {
                        frame.close();
                    }
                    break;
                }
                if (!candidateCommitted) {
                    if (boundedAv1Probe) {
                        try {
                            VideoBillboardPreview.commitAv1FirstFrameProbe(dec, frame);
                        } catch (IOException error) {
                            frameQueue.clear();
                            throw error;
                        }
                    }
                    candidateCommitted = true;
                    targetWidth = decodeSize.width();
                    targetHeight = decodeSize.height();
                    firstFrameLogged = true;
                    firstDecodedNanoTime = System.nanoTime();
                    activeCandidate = candidate;
                    actualDecoderBackend = actualBackend(dec);
                    performanceMonitor.start(firstDecodedNanoTime, candidateFps, actualDecoderBackend);
                    performanceMonitor.recordDecodedFrame(preferredDecodeSampleNanos(frame, waitNs));
                    LOGGER.debug("视频实例首个解码帧已提交: session={}, pts={}ms, wait={}ms, startOffset={}ms",
                            sessionId(), ptsNanos / 1_000_000L, waitNs / 1_000_000L, effectiveStartOffsetMillis);
                } else {
                    performanceMonitor.recordDecodedFrame(preferredDecodeSampleNanos(frame, waitNs));
                }
                warnIfUploadPumpStalled();
            }
            return candidateCommitted;
        } finally {
            try {
                closeCandidateBeforeFallback(dec, candidateCommitted, gen, candidate);
            } finally {
                if (decoder == dec) {
                    decoder = null;
                }
            }
        }
    }

    private static long preferredDecodeSampleNanos(VideoBillboardPreview.DecodedFrame frame, long waitNanos) {
        long nativeGet = frame != null ? frame.nativeGetNanos() : -1L;
        return nativeGet >= 0L ? nativeGet : Math.max(0L, waitNanos);
    }

    private static String actualBackend(AutoCloseable decoder) {
        if (decoder instanceof com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder nativeDecoder) {
            String actual = nativeDecoder.actualHwaccel();
            return actual == null || actual.isBlank() ? "unknown" : actual;
        }
        return decoder != null ? decoder.getClass().getSimpleName() : "unknown";
    }

    private void closeCandidateBeforeFallback(AutoCloseable candidateDecoder, boolean candidateCommitted,
            long gen, VideoCandidate candidate) throws Exception {
        if (candidateDecoder == null) {
            return;
        }
        if (candidateCommitted
                || !(candidateDecoder instanceof com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder nativeDecoder)) {
            CompletableFuture<Void> closeReturned = new CompletableFuture<>();
            CompletableFuture<Void> nativeTermination = candidateDecoder instanceof com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder nativeCandidate
                    ? nativeCandidate.terminationFuture() : CompletableFuture.completedFuture(null);
            physicalCloseHandoff.attachClose(closeReturned, nativeTermination, decodeExit);
            try {
                candidateDecoder.close();
                closeReturned.complete(null);
            } catch (Exception | Error error) {
                closeReturned.completeExceptionally(error);
                throw error;
            }
            return;
        }

        long closeStartedNanos = System.nanoTime();
        CompletableFuture<Void> nativeTermination = nativeDecoder.terminationFuture();
        long closeOperation = VideoCloseDiagnostics.global().begin(playbackSessionId, EnumSet.of(
                VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED,
                VideoCloseDiagnostics.Phase.NATIVE_TERMINATED), closeStartedNanos);
        nativeTermination.whenComplete((ignored, error) -> VideoCloseDiagnostics.global().complete(closeOperation,
                VideoCloseDiagnostics.Phase.NATIVE_TERMINATED, error, System.nanoTime()));
        nativeDecoder.requestClose();
        CompletableFuture<Void> candidateCloseReturned = new CompletableFuture<>();
        physicalCloseHandoff.attachClose(candidateCloseReturned, nativeTermination, decodeExit);
        Exception closeFailure = null;
        try {
            candidateDecoder.close();
            candidateCloseReturned.complete(null);
        } catch (Exception error) {
            closeFailure = error;
            candidateCloseReturned.completeExceptionally(error);
        } finally {
            VideoCloseDiagnostics.global().complete(closeOperation,
                    VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED, System.nanoTime());
        }
        long timeoutMillis = Math.max(1L, DECODER_RESTART_CLOSE_TIMEOUT_MILLIS);
        long closeElapsedNanos = Math.max(0L, System.nanoTime() - closeStartedNanos);
        VideoCandidateClosePolicy.Decision closeDecision = VideoCandidateClosePolicy.decide(
                true, VideoCandidateClosePolicy.completedNormally(nativeTermination),
                closeElapsedNanos, timeoutMillis);
        if (nativeTermination.isDone()
                && !VideoCandidateClosePolicy.completedNormally(nativeTermination)) {
            throw new CandidateCloseTimeoutException(candidate, nativeTermination,
                    "native termination completed exceptionally");
        }
        if (closeDecision == VideoCandidateClosePolicy.Decision.OPEN_NEXT) {
            if (closeFailure != null) {
                throw new CandidateCloseTimeoutException(candidate, nativeTermination,
                        "decoder close returned exceptionally", closeFailure);
            }
            return;
        }
        if (closeDecision == VideoCandidateClosePolicy.Decision.FAIL_CLOSED) {
            throw new CandidateCloseTimeoutException(candidate, nativeTermination);
        }
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long remainingNanos = timeoutNanos - closeElapsedNanos;
        try {
            nativeTermination.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            throw new CandidateCloseTimeoutException(candidate, nativeTermination);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("等待旧视频候选关闭时被中断", error);
        } catch (java.util.concurrent.ExecutionException error) {
            throw new CandidateCloseTimeoutException(candidate, nativeTermination,
                    "native termination completed exceptionally", error.getCause());
        }
        if (closeFailure != null) {
            throw new CandidateCloseTimeoutException(candidate, nativeTermination,
                    "decoder close returned exceptionally", closeFailure);
        }
    }

    private synchronized void failCandidateClose(long gen, CandidateCloseTimeoutException error) {
        // Always retain the native termination signal. A concurrent stop/restart
        // may invalidate the logical generation while the physical handle is
        // still alive; dropping this handoff would make the zombie invisible.
        VideoZombieCloseSupervisor.global().track(sessionId(), gen,
                CompletableFuture.completedFuture(null), error.nativeTermination, decodeExit);
        if (gen != generation.get() || !running || stopRequested) {
            return;
        }
        long failedGeneration = generation.incrementAndGet();
        restartInProgress = false;
        restartState = VideoDecoderRestartState.FAILED_CLOSE;
        networkFailure = false;
        terminalFailure = true;
        frameQueue.clear();
        LOGGER.error(
                "旧视频候选未正常收敛，禁止打开下一视频候选: session={} generation={} quality={} codec={} timeout={}ms",
                sessionId(), failedGeneration, error.candidate.quality(), error.candidate.codecId(),
                DECODER_RESTART_CLOSE_TIMEOUT_MILLIS);
    }

    private static final class CandidateCloseTimeoutException extends IOException {
        private final VideoCandidate candidate;
        private final CompletableFuture<Void> nativeTermination;

        private CandidateCloseTimeoutException(VideoCandidate candidate,
                CompletableFuture<Void> nativeTermination) {
            this(candidate, nativeTermination, "close timeout", null);
        }

        private CandidateCloseTimeoutException(VideoCandidate candidate,
                CompletableFuture<Void> nativeTermination, String reason) {
            this(candidate, nativeTermination, reason, null);
        }

        private CandidateCloseTimeoutException(VideoCandidate candidate,
                CompletableFuture<Void> nativeTermination, String reason, Throwable cause) {
            super("旧视频候选 native worker 未正常收敛: quality=" + candidate.quality()
                    + ", codec=" + candidate.codecId() + ", reason=" + reason, cause);
            this.candidate = candidate;
            this.nativeTermination = nativeTermination;
        }
    }

    private void handleDecodeFailure(long gen, Throwable error) {
        if (error instanceof OutOfMemoryError) {
            com.zhongbai233.net_music_can_play_bili.client.ClientMediaLifecycleHandler
                    .tripMemoryProtection("video decoder allocation failed: " + error.getMessage());
            LOGGER.error("视频会话内存分配失败并触发熔断: session={}", sessionId(), error);
        } else {
            if (gen != generation.get() || (!running && isInterruptedWait(error))) {
                return;
            }
            networkFailure = MediaNetworkFailureClassifier.isNetworkFailure(error);
            terminalFailure = true;
            if (networkFailure) {
                notifyNetworkFailure();
            }
            LOGGER.error("视频会话解码失败: session={}", sessionId(), error);
        }
    }

    private boolean shouldDropStaleStartupFrame(long ptsNanos) {
        long maxStartupLagNs = VideoPipelineProperties.startupDropLagMillis() * 1_000_000L;
        if (maxStartupLagNs <= 0L) {
            return false;
        }
        long playbackNs = playbackNanos();
        boolean drop = playbackNs - ptsNanos > maxStartupLagNs;
        return drop && frameQueue.isEmpty();
    }

    private void waitForDecodeLead(long frameIntervalNs, long gen) throws InterruptedException {
        long maxLeadNs = Math.max(frameIntervalNs * frameQueue.capacity(),
                VideoPipelineProperties.maxDecodeLeadMillis() * 1_000_000L);
        while (running && gen == generation.get() && frameQueue.isFull()
                && frameQueue.latestPtsNanos() - playbackNanos() > maxLeadNs) {
            warnIfUploadPumpStalled();
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(5L);
        }
    }

    private void warnIfUploadPumpStalled() {
        long thresholdNs = VideoPipelineProperties.uploadPumpWarnMillis() * 1_000_000L;
        long idleNs = System.nanoTime() - lastUploadPumpNanoTime;
        if (thresholdNs > 0L && frameQueue.isFull() && idleNs > thresholdNs) {
            lastUploadPumpNanoTime = System.nanoTime();
            LOGGER.warn("视频流水线上传泵疑似停滞: session={}, queue={}, latestPts={}ms, clock={}ms, idle={}ms",
                    sessionId(), frameQueue.size(), frameQueue.latestPtsNanos() / 1_000_000L,
                    playbackNanos() / 1_000_000L, idleNs / 1_000_000L);
        }
    }

    private boolean waitWhilePaused(long gen) {
        if (!isGamePaused()) {
            return running && gen == generation.get();
        }
        long pauseStartNs = System.nanoTime();
        performanceMonitor.pause(pauseStartNs);
        while (running && gen == generation.get() && isGamePaused()) {
            try {
                java.util.concurrent.TimeUnit.MILLISECONDS.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        startNanoTime += Math.max(0L, System.nanoTime() - pauseStartNs);
        performanceMonitor.resume(System.nanoTime());
        return running && gen == generation.get();
    }

    private boolean waitWhileOffscreen(long gen, boolean candidateCommitted) {
        if (!VideoRestartSuppressionPolicy.shouldPauseDecodeOffscreen(
                candidateCommitted, liveSource, OFFSCREEN_PAUSE_DECODE)
                || !isOffscreenPauseActive()) {
            return running && gen == generation.get();
        }
        long pauseStartNs = System.nanoTime();
        performanceMonitor.pause(pauseStartNs);
        if (!loggedOffscreenPause) {
            loggedOffscreenPause = true;
            LOGGER.debug("视频会话离屏暂停取帧: session={}, queue={}, media={}ms, master={}ms",
                    sessionId(), frameQueue.size(), mediaMillis(), anchor.timeline().mediaMillis());
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
        performanceMonitor.resume(System.nanoTime());
        if (pausedNs > 0L) {
            LOGGER.debug("视频会话离屏恢复取帧: session={}, paused={}ms, media={}ms, master={}ms",
                    sessionId(), pausedNs / 1_000_000L, mediaMillis(), anchor.timeline().mediaMillis());
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
        if (VideoRestartSuppressionPolicy.shouldPauseOffscreen(liveSource, OFFSCREEN_PAUSE_DECODE)
                && isOffscreenPauseActive()) {
            return;
        }
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
        if (!restartAllowed() || !hasFrame || !hasVideoConsumer() || RUNTIME_LAG_RESTART_MILLIS <= 0L) {
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

    private void restartDecoder(int nextWidth, int nextHeight, long restartOffsetMillis, boolean keepVisibleFrame) {
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
                && (frontTexture != null || yuvTextureSet != null);
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
            releaseTexture();
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
        startDecodeThread(gen, preserveVisibleFrame
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
                    "bili_video_" + sessionId() + "_yuv420p");
        } else {
            yuvTextureSet = new Nv12TextureSet(yTextureId, uTextureId, yTextureId,
                    "bili_video_" + sessionId() + "_nv12");
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
        frontTexture = new DynamicTexture("bili_video_" + sessionId() + "_front", targetWidth, targetHeight, false);
        backTexture = new DynamicTexture("bili_video_" + sessionId() + "_back", targetWidth, targetHeight, false);
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
        if (projectorPos != null && !consumers.containsProjector(projectorPos)) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        if (terminalFailure && NETWORK_ERROR_PLACEHOLDER_ENABLED) {
            return placeholderSnapshot(PlaceholderKind.NETWORK_ERROR);
        }
        if (!hasFrame) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        return currentFrameSnapshot();
    }

    VideoBillboardPreview.ProjectorFrameSnapshot displayFrameSnapshot(BlockPos projectorPos) {
        if (projectorPos != null && !consumers.containsProjector(projectorPos)) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        boolean loadingPlaceholderEnabled = VideoPipelineProperties.loadingPlaceholderEnabled();
        // Iris shaderpack 会捕获 BER 提交的多平面 YUV 几何；直接把这类帧交给
        // VideoProjectorRenderer 会造成不可见颜色写入或随视角变化的深度闪烁。
        // 与旧的全局提交路径保持一致：该兼容模式下用明确的警告占位图替代 YUV
        // 面片，而不是先因 hasFrame 返回实际视频，导致下方警告分支永远不可达。
        if (terminalFailure && NETWORK_ERROR_PLACEHOLDER_ENABLED) {
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

    VideoBillboardPreview.ProjectorFrameSnapshot realFrameSnapshot(BlockPos projectorPos) {
        if (projectorPos != null && !consumers.containsProjector(projectorPos)) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        return hasFrame ? currentFrameSnapshot() : VideoBillboardPreview.ProjectorFrameSnapshot.empty();
    }

    VideoBillboardPreview.ProjectorFrameSnapshot failurePlaceholderSnapshot() {
        return placeholderSnapshot(PlaceholderKind.NETWORK_ERROR);
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

    static VideoBillboardPreview.ProjectorFrameSnapshot idlePlaceholderSnapshot() {
        return new VideoBillboardPreview.ProjectorFrameSnapshot(true, false, IDLE_PLACEHOLDER_TEXTURE,
                null, null, null,
                com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA,
                LOADING_PLACEHOLDER_WIDTH, LOADING_PLACEHOLDER_HEIGHT, true, false, 0.0F);
    }

    VideoBillboardPreview.ProjectorFrameSnapshot turntableFrameSnapshot(BlockPos turntablePos) {
        if (turntablePos == null || !anchor.isForTurntable(turntablePos)) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        if (terminalFailure && NETWORK_ERROR_PLACEHOLDER_ENABLED) {
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
        if (terminalFailure && NETWORK_ERROR_PLACEHOLDER_ENABLED) {
            return placeholderSnapshot(PlaceholderKind.NETWORK_ERROR);
        }
        if (!hasFrame) {
            return VideoBillboardPreview.ProjectorFrameSnapshot.empty();
        }
        return currentFrameSnapshot();
    }

    void submit(SubmitCustomGeometryEvent event, Minecraft minecraft, Camera camera) {
        boolean renderable = false;
        boolean prewarm = false;
        List<BlockPos> projectorPositions = consumers.projectors();
        for (BlockPos pos : projectorPositions) {
            boolean berManagedProjector = VideoBillboardPreview.isProjectorRenderedByBer(pos);
            boolean submittedByBer = VideoBillboardPreview.wasProjectorRecentlySubmittedByBer(sessionId(), pos);
            if (VideoBerConsumerVisibilityPolicy.usesBerSubmission(berManagedProjector, submittedByBer)) {
                // BER 管理归属是持久状态，不能等同于本帧通过视锥。否则投影仪
                // 第一次出现后会永久保持 prewarm，离屏上传和解码永远不会暂停。
                // 中控台不是 VideoProjectorBlockEntity，也不会加入持久 projector 集合；
                // 它同 session、同位置的近期 BER submission 本身就是有效可见性证据。
                renderable |= submittedByBer;
                prewarm |= submittedByBer;
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
            } else if (VideoPipelineProperties.loadingPlaceholderEnabled()) {
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
        if (!running || !restartAllowed() || OFFSCREEN_RESUME_RESTART_LAG_NANOS <= 0L) {
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
                sessionId(), offscreenDurationNs / 1_000_000L, masterMillis, bestVideoMillis, restartOffsetMillis);
        restartDecoder(targetWidth, targetHeight, restartOffsetMillis, true);
    }

    private boolean restartAllowed() {
        long generationStart = decoderGenerationStartedNanoTime;
        long sinceStartMillis = generationStart > 0L
            ? Math.max(0L, (System.nanoTime() - generationStart) / 1_000_000L) : 0L;
        return VideoRestartSuppressionPolicy.allowsRestart(liveSource, restartInProgress,
                sinceStartMillis, DECODER_STABILIZATION_MILLIS);
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
        for (BlockPos pos : consumers.projectors()) {
            if (VideoBillboardPreview.drawCapturedProjectorYuvImmediate(event, sessionId(), pos, yuvTextureSet,
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
            LOGGER.debug("Iris/YUV: session={} 的投影仪 YUV 使用实例纹理 immediate 绘制，route={}, texture={}x{}",
                    sessionId(), route, yuvTextureSet.width(), yuvTextureSet.height());
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
        return hasFrame && yuvTextureSet != null && IrisShaderpackCompat.shouldApplyIrisYuvCompatibility();
    }

    boolean isWithinAudioRange(Minecraft minecraft) {
        if (minecraft.player == null || !hasVideoConsumer()) {
            return false;
        }
        return anchor.isWithinAudioRange(minecraft, consumers.projectors(),
                VideoBillboardPreview.AUDIO_SYNC_RANGE_SQR);
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
        }
    }

    boolean hasGuiConsumer() {
        return consumers.hasGuiConsumer();
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

    long generationForBench() {
        return generation.get();
    }

    long decoderStartOffsetMillisForBench() {
        return decoderStartOffsetMillis;
    }

    String restartStateForBench() {
        return restartState.name();
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
        if (stopRequested) {
            return;
        }
        stopRequested = true;
        restartState = VideoDecoderRestartState.STOPPED;
        restartInProgress = false;
        long now = System.nanoTime();
        AutoCloseable dec = decoder;
        CompletableFuture<Void> threadExit = decodeExit;
        CompletableFuture<Void> nativeTermination = dec instanceof com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder nativeDecoder
                ? nativeDecoder.terminationFuture() : CompletableFuture.completedFuture(null);
        CompletableFuture<Void> closeReturned = dec != null
                ? MediaCloseExecutor.closeAsyncStrict(dec, "video decoder " + sessionId())
                : CompletableFuture.completedFuture(null);
        CompletableFuture<Void> renderRelease = new CompletableFuture<>();
        physicalCloseHandoff.attachClose(closeReturned, nativeTermination, threadExit);
        physicalCloseHandoff.seal(renderRelease);
        EnumSet<VideoCloseDiagnostics.Phase> required = EnumSet.of(
                VideoCloseDiagnostics.Phase.FRAME_QUEUE_CLEARED,
                VideoCloseDiagnostics.Phase.RENDER_RELEASE_RETURNED);
        if (dec != null) {
            required.add(VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED);
        }
        if (threadExit != null && !threadExit.isDone()) {
            required.add(VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED);
        }
        if (nativeTermination != null && !nativeTermination.isDone()) {
            required.add(VideoCloseDiagnostics.Phase.NATIVE_TERMINATED);
        }
        long closeOperation = VideoCloseDiagnostics.global().begin(playbackSessionId, required, now);
        running = false;
        generation.incrementAndGet();
        frameQueue.clear();
        VideoCloseDiagnostics.global().complete(closeOperation,
                VideoCloseDiagnostics.Phase.FRAME_QUEUE_CLEARED, System.nanoTime());
        decoder = null;
        VideoCloseDiagnostics diagnostics = VideoCloseDiagnostics.global();
        if (required.contains(VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED)) {
            diagnostics.observe(closeOperation, VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED, closeReturned);
        }
        if (required.contains(VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED)) {
            // Do not re-check isDone here. The decode thread may complete between the required-phase snapshot and
            // observer registration; whenComplete deliberately closes that race by firing immediately.
            diagnostics.observe(closeOperation, VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED, threadExit);
        }
        if (required.contains(VideoCloseDiagnostics.Phase.NATIVE_TERMINATED)) {
            diagnostics.observe(closeOperation, VideoCloseDiagnostics.Phase.NATIVE_TERMINATED, nativeTermination);
        }
        Thread thread = decodeThread;
        if (thread != null) {
            thread.interrupt();
        }
        if (threadExit != null && !threadExit.isDone()) {
            scheduleDecodeExitDiagnostic(thread, threadExit);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            releaseTextureAndReport(closeOperation, renderRelease);
        } else {
            minecraft.execute(() -> releaseTextureAndReport(closeOperation, renderRelease));
        }
    }

    private void scheduleDecodeExitDiagnostic(Thread thread, CompletableFuture<Void> threadExit) {
        CompletableFuture.delayedExecutor(Math.max(1L, DECODER_RESTART_CLOSE_TIMEOUT_MILLIS),
                TimeUnit.MILLISECONDS).execute(() -> {
                    if (threadExit.isDone()) {
                        return;
                    }
                    if (thread == null) {
                        LOGGER.error("视频 decode exit 在 stop 后仍 pending，但实例没有 decode thread: session={}",
                                sessionId());
                        return;
                    }
                    StackTraceElement[] trace = thread.getStackTrace();
                    StringBuilder location = new StringBuilder();
                    int limit = Math.min(12, trace.length);
                    for (int index = 0; index < limit; index++) {
                        if (index > 0) {
                            location.append(" <- ");
                        }
                        location.append(trace[index]);
                    }
                    LOGGER.error(
                            "视频 decode exit 在 stop 后仍 pending: session={} thread={} alive={} state={} stack={}",
                            sessionId(), thread.getName(), thread.isAlive(), thread.getState(), location);
                });
    }

    synchronized void abandonBeforeStart() {
        if (running || stopRequested) {
            stop();
            return;
        }
        stopRequested = true;
        restartState = VideoDecoderRestartState.STOPPED;
        CompletableFuture<Void> renderRelease = new CompletableFuture<>();
        physicalCloseHandoff.seal(renderRelease);
        renderRelease.complete(null);
    }

    private void releaseTextureAndReport(long closeOperation, CompletableFuture<Void> renderRelease) {
        try {
            releaseTexture();
            renderRelease.complete(null);
        } catch (RuntimeException | Error error) {
            renderRelease.completeExceptionally(error);
            throw error;
        } finally {
            VideoCloseDiagnostics.global().complete(closeOperation,
                    VideoCloseDiagnostics.Phase.RENDER_RELEASE_RETURNED, System.nanoTime());
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
                    droppedFrames++;
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

        private long droppedFrames;

        synchronized long drainDroppedFrames() {
            long value = droppedFrames;
            droppedFrames = 0L;
            return value;
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
            DecodedVideoFrame latest = frames.peekLast();
            return latest != null ? latest.ptsNanos() : -1L;
        }
    }
}
