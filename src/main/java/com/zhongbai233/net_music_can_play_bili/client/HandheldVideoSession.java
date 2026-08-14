package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.ResolvedVideoStream;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoFallbackReason;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoPerformanceFallbackPolicy;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoPerformanceMonitor;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Physical decoder lifetime and performance policy for one handheld playback intent. */
final class HandheldVideoSession implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    final HandheldDeviceVideoState owner;
    final HandheldPlaybackKey key;
    final long decoderStartOffsetMillis;
    final AtomicBoolean closed = new AtomicBoolean(false);
    final AtomicReference<Fmp4NativeVideoDecoder> decoder = new AtomicReference<>();
    final CompletableFuture<Void> decodeExit = new CompletableFuture<>();
    final AtomicReference<CompletableFuture<Void>> latestNativeTermination = new AtomicReference<>(
            CompletableFuture.completedFuture(null));
    final CompletableFuture<Void> physicalTermination = new CompletableFuture<>();
    final VideoPerformanceMonitor performanceMonitor = new VideoPerformanceMonitor();
    final boolean h264CandidateAvailable;
    final AtomicBoolean performanceFallbackRequested = new AtomicBoolean(false);
    volatile boolean performanceFallbackLocked;
    volatile int activeCodecId;
    volatile int actualQuality;
    volatile String actualBackend = "unknown";
    volatile String fallbackReason = "";
    volatile String pendingFallbackReason = "performance";
    volatile boolean performanceNoH264Notified;

    HandheldVideoSession(HandheldDeviceVideoState owner, HandheldPlaybackKey key, long decoderStartOffsetMillis,
            List<BiliVideoStreamResolver.VideoCandidate> candidates) {
        this.owner = Objects.requireNonNull(owner);
        this.key = Objects.requireNonNull(key);
        this.decoderStartOffsetMillis = Math.max(0L, decoderStartOffsetMillis);
        List<BiliVideoStreamResolver.VideoCandidate> safeCandidates = candidates != null ? candidates : List.of();
        this.h264CandidateAvailable = safeCandidates.stream().anyMatch(candidate -> candidate.codecId() == 7);
        if (h264CandidateAvailable && safeCandidates.stream().noneMatch(candidate -> candidate.codecId() == 13)) {
            fallbackReason = VideoFallbackReason.NO_AV1_STREAM;
        }
    }

    void startPerformanceObservation(ResolvedVideoStream stream, Fmp4NativeVideoDecoder decoder, long nowNanos) {
        activeCodecId = stream.codecId();
        actualQuality = stream.quality();
        actualBackend = decoder != null && decoder.actualHwaccel() != null ? decoder.actualHwaccel() : "unknown";
        performanceMonitor.start(nowNanos, stream.fps(), actualBackend);
    }

    boolean evaluatePerformance(HandheldDeviceVideoState state, long nowNanos) {
        performanceMonitor.sampleNativeResources(nowNanos);
        VideoPerformanceFallbackPolicy.Snapshot snapshot = performanceMonitor.snapshot(nowNanos);
        VideoPerformanceFallbackPolicy.Decision decision = VideoPerformanceFallbackPolicy.decide(
                snapshot, activeCodecId == 13, h264CandidateAvailable, performanceFallbackLocked);
        if (decision == VideoPerformanceFallbackPolicy.Decision.KEEP_NO_H264 && !performanceNoH264Notified) {
            performanceNoH264Notified = true;
            fallbackReason = VideoFallbackReason.NO_H264_CANDIDATE;
            state.statusText = MP4HandheldVideoClient.playingStatus(this, actualQuality, activeCodecId, actualBackend);
            LOGGER.warn("MP4 横屏 AV1 性能超预算但同次 playurl 无 H.264 候选: session={} backend={}",
                    key.sessionId(), actualBackend);
        }
        if (!decision.shouldFallback() || !performanceFallbackRequested.compareAndSet(false, true)) {
            return false;
        }
        pendingFallbackReason = decision.reason();
        state.statusText = "AV1 性能不足，切换 H.264...";
        Fmp4NativeVideoDecoder attached = decoder();
        if (attached != null) {
            attached.requestClose();
        }
        LOGGER.warn(
                "MP4 横屏 AV1 性能预算触发: session={} reason={} backend={} actualFps={}/{} avg={}ms p95={}ms starvation={} dropped={} driftGrowth={}ms nativePeak={} surfaces={}",
                key.sessionId(), pendingFallbackReason, snapshot.backend(),
                String.format(java.util.Locale.ROOT, "%.2f", snapshot.actualDecodeFps()), snapshot.targetFps(),
                String.format(java.util.Locale.ROOT, "%.2f", snapshot.averageDecodeMillis()),
                String.format(java.util.Locale.ROOT, "%.2f", snapshot.p95DecodeMillis()),
                snapshot.starvationCount(), snapshot.droppedFrames(), snapshot.syncDriftGrowthMillis(),
                snapshot.nativeFrameBytesPeak(), snapshot.nativeSurfacePeak());
        return true;
    }

    void observeTimelineAndEvaluate(HandheldDeviceVideoState state, long visualMillis) {
        long latestMillis = HandheldVideoFrameTimeline.latestFrameMillis(state, this);
        if (visualMillis >= 0L && latestMillis >= 0L) {
            performanceMonitor.recordSyncDriftMillis(visualMillis - latestMillis);
        }
        evaluatePerformance(state, System.nanoTime());
    }

    void lockPerformanceFallback(String reason) {
        performanceFallbackLocked = true;
        performanceFallbackRequested.set(false);
        fallbackReason = reason == null || reason.isBlank() ? "performance" : reason;
    }

    boolean attachDecoder(Fmp4NativeVideoDecoder value) {
        Objects.requireNonNull(value);
        synchronized (owner.lifecycleLock) {
            if (closed.get() || owner.activeSession != this || !key.equals(owner.activeKey)) {
                value.requestClose();
                return false;
            }
            decoder.set(value);
            return true;
        }
    }

    void trackNative(CompletableFuture<Void> nativeTermination) {
        latestNativeTermination.set(Objects.requireNonNull(nativeTermination));
    }

    void detachDecoder(Fmp4NativeVideoDecoder value) {
        decoder.compareAndSet(value, null);
    }

    Fmp4NativeVideoDecoder decoder() {
        return decoder.get();
    }

    void completeDecodeTaskExit() {
        CompletableFuture<Void> lastNativeTermination = latestNativeTermination.get();
        decodeExit.complete(null);
        lastNativeTermination.whenComplete((ignored, error) -> {
            if (error == null) {
                physicalTermination.complete(null);
            } else {
                physicalTermination.completeExceptionally(error);
            }
        });
    }

    @Override
    public void close() {
        synchronized (owner.lifecycleLock) {
            closed.set(true);
            Fmp4NativeVideoDecoder attached = decoder.get();
            if (attached != null) {
                attached.requestClose();
            }
        }
    }
}
