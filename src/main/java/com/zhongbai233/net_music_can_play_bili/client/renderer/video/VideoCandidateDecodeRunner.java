package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.VideoCandidate;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.stream.MediaNetworkFailureClassifier;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Owns startup candidate selection, decode pacing, and the fail-closed fallback barrier. */
final class VideoCandidateDecodeRunner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final VideoPipelineProperties.Timing TIMING = VideoPipelineProperties.timing();
    private static final VideoPipelineProperties.Offscreen OFFSCREEN = VideoPipelineProperties.offscreen();
    private static final VideoPipelineProperties.Presentation PRESENTATION = VideoPipelineProperties.presentation();
    private static final long CLOSE_TIMEOUT_MILLIS = TIMING.decoderRestartCloseTimeoutMillis();
    private static final long OFFSCREEN_GRACE_NANOS = OFFSCREEN.graceMillis() * 1_000_000L;

    private final VideoPlaybackInstance owner;

    VideoCandidateDecodeRunner(VideoPlaybackInstance owner) {
        this.owner = owner;
    }

    void start(long generation, String threadName) {
        CompletableFuture<Void> exit = new CompletableFuture<>();
        owner.decodeExit = exit;
        owner.physicalCloseHandoff.beginDecode(exit);
        Thread thread = NetMusicThreadFactory.daemonThread(threadName, () -> {
            try {
                decode(generation);
            } finally {
                exit.complete(null);
            }
        });
        owner.decodeThread = thread;
        try {
            thread.start();
        } catch (RuntimeException | Error startFailure) {
            owner.decodeThread = null;
            exit.completeExceptionally(startFailure);
            throw startFailure;
        }
    }

    private void decode(long generation) {
        Exception lastStartupFailure = null;
        List<VideoCandidate> operationalCandidates = VideoStartupFallbackPolicy.operationalCandidates(owner.candidates,
                PRESENTATION.maxSourceWidth(), PRESENTATION.maxSourceHeight());
        if (owner.performanceFallbackLocked) {
            operationalCandidates = VideoStartupFallbackPolicy.lockedH264Candidates(operationalCandidates);
        }
        for (VideoCandidate candidate : operationalCandidates) {
            if (!owner.running || generation != owner.generation.get()) {
                return;
            }
            if (!waitForVisualDemand(generation)) {
                return;
            }
            try {
                if (decodeCandidate(generation, candidate)) {
                    return;
                }
            } catch (Exception error) {
                if (generation != owner.generation.get() || !owner.running || isInterruptedWait(error)) {
                    return;
                }
                if (error instanceof VideoBillboardPreview.CandidateResourceCloseException closeFailure) {
                    failCandidateClose(generation, new VideoCandidateCloseTimeoutException(candidate,
                            closeFailure.nativeTermination, closeFailure.getMessage(), closeFailure));
                    return;
                }
                if (error instanceof VideoCandidateCloseTimeoutException closeTimeout) {
                    failCandidateClose(generation, closeTimeout);
                    return;
                }
                if (owner.firstFrameLogged) {
                    handleDecodeFailure(generation, error);
                    return;
                }
                lastStartupFailure = error;
                if (candidate.codecId() == 13) {
                    owner.fallbackReason = VideoFallbackReason.classifyAv1StartupFailure(error,
                            operationalCandidates.stream().anyMatch(next -> next.codecId() == 7));
                }
                LOGGER.warn("视频实例候选首帧失败，尝试下一候选: session={} quality={} codec={} source={}x{} reason={}",
                        owner.sessionId(), candidate.quality(), candidate.codecId(), candidate.sourceWidth(),
                        candidate.sourceHeight(), error.toString());
            }
        }
        if (lastStartupFailure != null) {
            handleDecodeFailure(generation, lastStartupFailure);
        }
        if (generation == owner.generation.get()) {
            owner.running = false;
            owner.decoder = null;
        }
    }

    private boolean waitForVisualDemand(long generation) {
        while (owner.running && generation == owner.generation.get() && !owner.prewarmVisible) {
            try {
                TimeUnit.MILLISECONDS.sleep(25L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (!owner.running || generation != owner.generation.get()) {
            return false;
        }
        owner.grantDecodeAdmission();
        return true;
    }

    private boolean decodeCandidate(long generation, VideoCandidate candidate) throws Exception {
        int candidateFps = Math.max(1, candidate.fps());
        long frameIntervalNs = Math.max(1L, 1_000_000_000L / candidateFps);
        long frameIndex = 0L;
        long effectiveStartOffsetMillis = owner.effectiveDecoderStartOffsetMillis();
        owner.decoderStartOffsetMillis = effectiveStartOffsetMillis;
        owner.adaptiveRestartOffsetMillis = -1L;
        boolean candidateCommitted = false;
        VideoStartupFallbackPolicy.DecodeSize decodeSize = VideoStartupFallbackPolicy.candidateDecodeSize(
                owner.targetWidth, owner.targetHeight, candidate.sourceWidth(), candidate.sourceHeight());
        AutoCloseable decoder = VideoBillboardPreview.openDecoder(candidate.url(), decodeSize.width(),
                decodeSize.height(), candidateFps, candidate.codecId(), owner.preferNative, owner.decoderOverride,
                effectiveStartOffsetMillis, owner.totalMillis, owner.consumers.hasGuiConsumer(), candidate.decodeMode());
        if (decoder instanceof Fmp4NativeVideoDecoder nativeDecoder) {
            owner.physicalCloseHandoff.attachDecoder(nativeDecoder.terminationFuture());
        }
        try {
            owner.decoder = decoder;
            while (owner.running && generation == owner.generation.get()) {
                if (!waitWhilePaused(generation) || !owner.hasVideoConsumer()
                        || !waitWhileOffscreen(generation, candidateCommitted)) {
                    break;
                }
                if (frameIndex > 0L) {
                    waitForDecodeLead(frameIntervalNs, generation);
                }
                long waitStartNs = System.nanoTime();
                boolean boundedAv1Probe = !candidateCommitted
                        && VideoStartupFallbackPolicy.requiresBoundedFirstFrameProbe(candidate);
                VideoBillboardPreview.DecodedFrame frame = boundedAv1Probe
                        ? VideoBillboardPreview.nextDecodedFrameWithAv1FirstFrameProbe(decoder)
                        : VideoBillboardPreview.nextDecodedFrame(decoder);
                long waitNs = System.nanoTime() - waitStartNs;
                if (frame == null) {
                    if (!owner.firstFrameLogged) {
                        throw new IOException("候选在输出首帧前结束");
                    }
                    return true;
                }
                frameIndex++;
                long ptsNanos = frame.ptsNanos() >= 0L ? frame.ptsNanos() : frameIndex * frameIntervalNs;
                if (!owner.firstFrameLogged && shouldDropStaleStartupFrame(ptsNanos)) {
                    rejectAndClose(decoder, frame, boundedAv1Probe);
                    continue;
                }
                boolean offered;
                try {
                    offered = owner.frameQueue.offer(new DecodedVideoFrame(frameIndex, ptsNanos, frame),
                            () -> owner.running && generation == owner.generation.get());
                } catch (InterruptedException error) {
                    rejectAndClose(decoder, frame, boundedAv1Probe);
                    throw error;
                }
                if (!offered) {
                    rejectAndClose(decoder, frame, boundedAv1Probe);
                    break;
                }
                if (!candidateCommitted) {
                    if (boundedAv1Probe) {
                        try {
                            VideoBillboardPreview.commitAv1FirstFrameProbe(decoder, frame);
                        } catch (IOException error) {
                            owner.frameQueue.clear();
                            throw error;
                        }
                    }
                    candidateCommitted = true;
                    owner.targetWidth = decodeSize.width();
                    owner.targetHeight = decodeSize.height();
                    owner.firstFrameLogged = true;
                    owner.firstDecodedNanoTime = System.nanoTime();
                    owner.activeCandidate = candidate;
                    owner.actualDecoderBackend = actualBackend(decoder);
                    owner.performanceMonitor.start(owner.firstDecodedNanoTime, candidateFps, owner.actualDecoderBackend);
                    owner.performanceMonitor.recordDecodedFrame(preferredDecodeSampleNanos(frame, waitNs));
                    LOGGER.debug("视频实例首个解码帧已提交: session={}, pts={}ms, wait={}ms, startOffset={}ms",
                            owner.sessionId(), ptsNanos / 1_000_000L, waitNs / 1_000_000L,
                            effectiveStartOffsetMillis);
                } else {
                    owner.performanceMonitor.recordDecodedFrame(preferredDecodeSampleNanos(frame, waitNs));
                }
                warnIfUploadPumpStalled();
            }
            return candidateCommitted;
        } finally {
            try {
                closeCandidateBeforeFallback(decoder, candidateCommitted, generation, candidate);
            } finally {
                if (owner.decoder == decoder) {
                    owner.decoder = null;
                }
            }
        }
    }

    private static void rejectAndClose(AutoCloseable decoder, VideoBillboardPreview.DecodedFrame frame,
            boolean boundedAv1Probe) throws IOException {
        try {
            if (boundedAv1Probe) {
                VideoBillboardPreview.rejectAv1FirstFrameProbeFrame(decoder, frame);
            }
        } finally {
            frame.close();
        }
    }

    private static long preferredDecodeSampleNanos(VideoBillboardPreview.DecodedFrame frame, long waitNanos) {
        long nativeGet = frame != null ? frame.nativeGetNanos() : -1L;
        return nativeGet >= 0L ? nativeGet : Math.max(0L, waitNanos);
    }

    private static String actualBackend(AutoCloseable decoder) {
        if (decoder instanceof Fmp4NativeVideoDecoder nativeDecoder) {
            String actual = nativeDecoder.actualHwaccel();
            return actual == null || actual.isBlank() ? "unknown" : actual;
        }
        return decoder != null ? decoder.getClass().getSimpleName() : "unknown";
    }

    private void closeCandidateBeforeFallback(AutoCloseable candidateDecoder, boolean candidateCommitted,
            long generation, VideoCandidate candidate) throws Exception {
        if (candidateDecoder == null) {
            return;
        }
        if (candidateCommitted || !(candidateDecoder instanceof Fmp4NativeVideoDecoder nativeDecoder)) {
            CompletableFuture<Void> closeReturned = new CompletableFuture<>();
            CompletableFuture<Void> nativeTermination = candidateDecoder instanceof Fmp4NativeVideoDecoder nativeCandidate
                    ? nativeCandidate.terminationFuture() : CompletableFuture.completedFuture(null);
            owner.physicalCloseHandoff.attachClose(closeReturned, nativeTermination, owner.decodeExit);
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
        long closeOperation = VideoCloseDiagnostics.global().begin(owner.playbackSessionId, EnumSet.of(
                VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED,
                VideoCloseDiagnostics.Phase.NATIVE_TERMINATED), closeStartedNanos);
        nativeTermination.whenComplete((ignored, error) -> VideoCloseDiagnostics.global().complete(closeOperation,
                VideoCloseDiagnostics.Phase.NATIVE_TERMINATED, error, System.nanoTime()));
        nativeDecoder.requestClose();
        CompletableFuture<Void> candidateCloseReturned = new CompletableFuture<>();
        owner.physicalCloseHandoff.attachClose(candidateCloseReturned, nativeTermination, owner.decodeExit);
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
        long timeoutMillis = Math.max(1L, CLOSE_TIMEOUT_MILLIS);
        long closeElapsedNanos = Math.max(0L, System.nanoTime() - closeStartedNanos);
        VideoCandidateClosePolicy.Decision closeDecision = VideoCandidateClosePolicy.decide(true,
                VideoCandidateClosePolicy.completedNormally(nativeTermination), closeElapsedNanos, timeoutMillis);
        if (nativeTermination.isDone() && !VideoCandidateClosePolicy.completedNormally(nativeTermination)) {
            throw new VideoCandidateCloseTimeoutException(candidate, nativeTermination,
                    "native termination completed exceptionally");
        }
        if (closeDecision == VideoCandidateClosePolicy.Decision.OPEN_NEXT) {
            if (closeFailure != null) {
                throw new VideoCandidateCloseTimeoutException(candidate, nativeTermination,
                        "decoder close returned exceptionally", closeFailure);
            }
            return;
        }
        if (closeDecision == VideoCandidateClosePolicy.Decision.FAIL_CLOSED) {
            throw new VideoCandidateCloseTimeoutException(candidate, nativeTermination);
        }
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - closeElapsedNanos;
        try {
            nativeTermination.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            throw new VideoCandidateCloseTimeoutException(candidate, nativeTermination);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("等待旧视频候选关闭时被中断", error);
        } catch (ExecutionException error) {
            throw new VideoCandidateCloseTimeoutException(candidate, nativeTermination,
                    "native termination completed exceptionally", error.getCause());
        }
        if (closeFailure != null) {
            throw new VideoCandidateCloseTimeoutException(candidate, nativeTermination,
                    "decoder close returned exceptionally", closeFailure);
        }
    }

    private void failCandidateClose(long generation, VideoCandidateCloseTimeoutException error) {
        synchronized (owner) {
            VideoZombieCloseSupervisor.global().track(owner.sessionId(), generation,
                    CompletableFuture.completedFuture(null), error.nativeTermination, owner.decodeExit);
            if (generation != owner.generation.get() || !owner.running || owner.stopRequested) {
                return;
            }
            long failedGeneration = owner.generation.incrementAndGet();
            owner.restartInProgress = false;
            owner.restartState = VideoDecoderRestartState.FAILED_CLOSE;
            owner.networkFailure = false;
            owner.terminalFailure = true;
            owner.frameQueue.clear();
            LOGGER.error("旧视频候选未正常收敛，禁止打开下一视频候选: session={} generation={} quality={} codec={} timeout={}ms",
                    owner.sessionId(), failedGeneration, error.candidate.quality(), error.candidate.codecId(),
                    CLOSE_TIMEOUT_MILLIS);
        }
    }

    private void handleDecodeFailure(long generation, Throwable error) {
        if (error instanceof OutOfMemoryError) {
            com.zhongbai233.net_music_can_play_bili.client.ClientMediaLifecycleHandler
                    .tripMemoryProtection("video decoder allocation failed: " + error.getMessage());
            LOGGER.error("视频会话内存分配失败并触发熔断: session={}", owner.sessionId(), error);
            return;
        }
        if (generation != owner.generation.get() || (!owner.running && isInterruptedWait(error))) {
            return;
        }
        owner.networkFailure = MediaNetworkFailureClassifier.isNetworkFailure(error);
        owner.terminalFailure = true;
        if (owner.networkFailure) {
            owner.notifyNetworkFailure();
        }
        LOGGER.error("视频会话解码失败: session={}", owner.sessionId(), error);
    }

    private boolean shouldDropStaleStartupFrame(long ptsNanos) {
        long maxStartupLagNs = VideoPipelineProperties.startupDropLagMillis() * 1_000_000L;
        return maxStartupLagNs > 0L && owner.playbackNanos() - ptsNanos > maxStartupLagNs
                && owner.frameQueue.isEmpty();
    }

    private void waitForDecodeLead(long frameIntervalNs, long generation) throws InterruptedException {
        long maxLeadNs = Math.max(frameIntervalNs * owner.frameQueue.capacity(),
                VideoPipelineProperties.maxDecodeLeadMillis() * 1_000_000L);
        while (owner.running && generation == owner.generation.get() && owner.frameQueue.isFull()
                && owner.frameQueue.latestPtsNanos() - owner.playbackNanos() > maxLeadNs) {
            warnIfUploadPumpStalled();
            TimeUnit.MILLISECONDS.sleep(5L);
        }
    }

    private void warnIfUploadPumpStalled() {
        long thresholdNs = VideoPipelineProperties.uploadPumpWarnMillis() * 1_000_000L;
        long idleNs = System.nanoTime() - owner.lastUploadPumpNanoTime;
        if (thresholdNs > 0L && owner.frameQueue.isFull() && idleNs > thresholdNs) {
            owner.lastUploadPumpNanoTime = System.nanoTime();
            LOGGER.warn("视频流水线上传泵疑似停滞: session={}, queue={}, latestPts={}ms, clock={}ms, idle={}ms",
                    owner.sessionId(), owner.frameQueue.size(), owner.frameQueue.latestPtsNanos() / 1_000_000L,
                    owner.playbackNanos() / 1_000_000L, idleNs / 1_000_000L);
        }
    }

    private boolean waitWhilePaused(long generation) {
        if (!isGamePaused()) {
            return owner.running && generation == owner.generation.get();
        }
        long pauseStartNs = System.nanoTime();
        owner.performanceMonitor.pause(pauseStartNs);
        while (owner.running && generation == owner.generation.get() && isGamePaused()) {
            try {
                TimeUnit.MILLISECONDS.sleep(25L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        owner.startNanoTime += Math.max(0L, System.nanoTime() - pauseStartNs);
        owner.performanceMonitor.resume(System.nanoTime());
        return owner.running && generation == owner.generation.get();
    }

    private boolean waitWhileOffscreen(long generation, boolean candidateCommitted) {
        if (!VideoRestartSuppressionPolicy.shouldPauseDecodeOffscreen(candidateCommitted, owner.liveSource,
                OFFSCREEN.pauseDecode()) || !isOffscreenPauseActive()) {
            return owner.running && generation == owner.generation.get();
        }
        long pauseStartNs = System.nanoTime();
        owner.performanceMonitor.pause(pauseStartNs);
        if (!owner.loggedOffscreenPause) {
            owner.loggedOffscreenPause = true;
            LOGGER.debug("视频会话离屏暂停取帧: session={}, queue={}, media={}ms, master={}ms",
                    owner.sessionId(), owner.frameQueue.size(), owner.mediaMillis(),
                    owner.anchor.timeline().mediaMillis());
        }
        while (owner.running && generation == owner.generation.get() && isOffscreenPauseActive()) {
            try {
                TimeUnit.MILLISECONDS.sleep(25L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        long pausedNs = System.nanoTime() - pauseStartNs;
        owner.performanceMonitor.resume(System.nanoTime());
        if (pausedNs > 0L) {
            LOGGER.debug("视频会话离屏恢复取帧: session={}, paused={}ms, media={}ms, master={}ms",
                    owner.sessionId(), pausedNs / 1_000_000L, owner.mediaMillis(),
                    owner.anchor.timeline().mediaMillis());
        }
        return owner.running && generation == owner.generation.get();
    }

    boolean isOffscreenPauseActive() {
        if (owner.prewarmVisible) {
            return false;
        }
        long lastVisible = owner.lastVisibleNanoTime;
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
}
