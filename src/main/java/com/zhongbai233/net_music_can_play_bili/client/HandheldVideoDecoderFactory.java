package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.PadDiagnosticsProperties;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.ResolvedVideoStream;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoZombieCloseSupervisor;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoPipelineConfig;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Opens handheld native decoders and enforces candidate physical-close barriers. */
final class HandheldVideoDecoderFactory {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HandheldVideoPipelineConfig CONFIG = HandheldVideoPipelineConfig.fromSystemProperties(
            "ncpb.mp4.video");
    private static final VideoClientProperties.Handheld VIDEO_PROPERTIES = VideoClientProperties.handheld();
    private static final boolean PAD_VIDEO_DEBUG_LOG = PadDiagnosticsProperties.videoDebugLogEnabled();
    private static final long CANDIDATE_CLOSE_TIMEOUT_MILLIS = 3_000L;
    private static final AtomicLong CLOSE_SEQUENCE = new AtomicLong();

    private HandheldVideoDecoderFactory() {
    }

    static boolean requiresBoundedAv1FirstFrameProbe(ResolvedVideoStream stream) {
        return stream.codecId() == 13
                && stream.decodeMode() == BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED;
    }

    static void closeCandidate(Fmp4NativeVideoDecoder decoder, boolean firstFrameAccepted,
            ResolvedVideoStream stream, HandheldVideoSession session, boolean requireConvergenceBarrier)
            throws IOException {
        if (firstFrameAccepted && !requireConvergenceBarrier) {
            decoder.close();
            return;
        }
        long closeStartedNanos = System.nanoTime();
        CompletableFuture<Void> nativeTermination = decoder.terminationFuture();
        long closeOperation = VideoCloseDiagnostics.global().begin(session.key.sessionId(), EnumSet.of(
                VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED,
                VideoCloseDiagnostics.Phase.NATIVE_TERMINATED), closeStartedNanos);
        nativeTermination.whenComplete((ignored, error) -> {
            if (error == null) {
                VideoCloseDiagnostics.global().complete(closeOperation,
                        VideoCloseDiagnostics.Phase.NATIVE_TERMINATED, System.nanoTime());
            }
        });
        decoder.requestClose();
        RuntimeException closeFailure = null;
        try {
            decoder.close();
        } catch (RuntimeException error) {
            closeFailure = error;
        } finally {
            VideoCloseDiagnostics.global().complete(closeOperation,
                    VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED, System.nanoTime());
        }
        if (HandheldDecoderAdmissionPolicy.completedNormally(nativeTermination)) {
            if (closeFailure != null) {
                throw closeFailure;
            }
            return;
        }
        if (nativeTermination.isDone()) {
            trackZombie(session, closeOperation, nativeTermination);
            throw new HandheldCandidateCloseFailureException(stream,
                    "native termination completed exceptionally");
        }
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(CANDIDATE_CLOSE_TIMEOUT_MILLIS)
                - Math.max(0L, System.nanoTime() - closeStartedNanos);
        if (remainingNanos <= 0L) {
            trackZombie(session, closeOperation, nativeTermination);
            throw new HandheldCandidateCloseTimeoutException(stream);
        }
        try {
            nativeTermination.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            trackZombie(session, closeOperation, nativeTermination);
            throw new HandheldCandidateCloseTimeoutException(stream);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            trackZombie(session, closeOperation, nativeTermination);
            throw new HandheldCandidateCloseFailureException(stream, "close barrier interrupted", error);
        } catch (ExecutionException error) {
            trackZombie(session, closeOperation, nativeTermination);
            throw new HandheldCandidateCloseFailureException(stream,
                    "native termination completed exceptionally", error.getCause());
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    static long preferredDecodeSampleNanos(Fmp4NativeVideoDecoder.DecodedFrame frame) {
        if (frame == null) {
            return 0L;
        }
        return frame.nativeGetNanos() >= 0L ? frame.nativeGetNanos() : Math.max(0L, frame.queueWaitNanos());
    }

    static Fmp4NativeVideoDecoder open(HandheldVideoSession session, ResolvedVideoStream stream,
            MP4HandheldVideoClient.DecodeSize decodeSize, Fmp4NativeVideoDecoder.OutputFormat outputFormat,
            long elapsedMillis, long totalMillis) throws IOException {
        String sessionId = session.key.sessionId();
        IOException last = null;
        for (String hwaccel : hwaccelCandidates(sessionId, stream.decodeMode())) {
            Fmp4NativeVideoDecoder opened = null;
            try {
                if (PAD_VIDEO_DEBUG_LOG && PadClientMediaSessionIds.isPadSession(sessionId)) {
                    LOGGER.info(
                            "Pad video native decoder open: session={} hwaccel={} codec={} target={}x{} format={} offset={}ms",
                            sessionId, hwaccel, stream.codecId(), decodeSize.width(), decodeSize.height(),
                            outputFormat, elapsedMillis);
                }
                opened = new Fmp4NativeVideoDecoder(
                        stream.url(), stream.codecId(), decodeSize.width(), decodeSize.height(), CONFIG.maxFrames(),
                        true, outputFormat, hwaccel, elapsedMillis, totalMillis, stream.fps());
                session.trackNative(opened.terminationFuture());
                if (stream.decodeMode() == BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED
                        && !opened.isHardwareAccelerated()) {
                    String actualHwaccel = opened.actualHwaccel();
                    closeRejected(session, stream, opened,
                            "rejected hardware backend did not terminate normally");
                    throw new IOException("候选要求硬件解码但 native backend 未启用硬解: requested="
                            + hwaccel + ", actual=" + actualHwaccel);
                }
                return opened;
            } catch (HandheldCandidateCloseFailureException failure) {
                throw failure;
            } catch (IOException e) {
                last = e;
                LOGGER.warn("MP4 横屏 native 解码器启动失败 hwaccel={}，尝试下一个候选: {}", hwaccel, e.toString());
            } catch (RuntimeException error) {
                if (opened != null) {
                    closeRejected(session, stream, opened,
                            "decoder validation failed and close did not terminate normally");
                }
                throw error;
            }
        }
        throw last != null ? last : new IOException("Native handheld video decoder unavailable");
    }

    private static void closeRejected(HandheldVideoSession session, ResolvedVideoStream stream,
            Fmp4NativeVideoDecoder decoder, String failureReason) throws HandheldCandidateCloseFailureException {
        CompletableFuture<Void> termination = decoder.terminationFuture();
        decoder.close();
        if (!HandheldDecoderAdmissionPolicy.completedNormally(termination)) {
            trackZombie(session, CLOSE_SEQUENCE.incrementAndGet(), termination);
            throw new HandheldCandidateCloseFailureException(stream, failureReason);
        }
    }

    private static void trackZombie(HandheldVideoSession session, long closeOperation,
            CompletableFuture<Void> nativeTermination) {
        VideoZombieCloseSupervisor.global().track(session.key.sessionId(), closeOperation,
                CompletableFuture.completedFuture(null), nativeTermination, session.decodeExit);
    }

    private static String[] hwaccelCandidates(String sessionId, BiliVideoStreamResolver.DecodeMode decodeMode) {
        if (decodeMode == BiliVideoStreamResolver.DecodeMode.SOFTWARE_ONLY) {
            return new String[] { "none" };
        }
        String requested = PadClientMediaSessionIds.isPadSession(sessionId)
                ? VIDEO_PROPERTIES.padNativeHwaccel()
                : VIDEO_PROPERTIES.nativeHwaccel();
        if (requested.isBlank() || "none".equalsIgnoreCase(requested) || "off".equalsIgnoreCase(requested)) {
            return new String[] { "none" };
        }
        if ("auto".equalsIgnoreCase(requested)) {
            String[] candidates = VideoFeatureFlags.requestedHwaccelCandidates();
            return decodeMode == BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED
                    ? java.util.Arrays.stream(candidates).filter(value -> !"none".equalsIgnoreCase(value))
                            .toArray(String[]::new)
                    : candidates;
        }
        return decodeMode == BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED
                ? new String[] { requested }
                : new String[] { requested, "none" };
    }
}
