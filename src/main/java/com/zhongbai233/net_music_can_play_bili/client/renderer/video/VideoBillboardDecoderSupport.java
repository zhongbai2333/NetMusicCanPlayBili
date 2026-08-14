package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.stream.MediaNetworkFailureClassifier;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.DecodeMode;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureFlags;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Decoder construction, legacy decode loop, AV1 probe adapters, and fallback restart decisions. */
abstract class VideoBillboardDecoderSupport extends VideoBillboardUploadSupport {
    protected static void decodeLoop(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, long startOffsetMillis, long totalMillis, long generation,
            boolean catchUpDropsEnabled, boolean forceRgbaOutput) {
        if (CPU_BARS) {
            decodeCpuBarsLoop(targetWidth, targetHeight, fps, generation);
            return;
        }
        long frameIntervalNs = fps > 0 ? Math.max(1L, 1_000_000_000L / fps) : 50_000_000L;
        long frameIndex = 0L;
        int consecutiveBadFrames = 0;
        AutoCloseable decoder = null;
        try {
            decoder = openDecoder(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative,
                    decoderOverride, startOffsetMillis, totalMillis, forceRgbaOutput);
            if (!LEGACY_WORKER.bindDecoder(generation, decoder)) {
                return;
            }
            warnNativeOffsetLimitation(decoder, startOffsetMillis);
            while (LEGACY_WORKER.isActive(generation)) {
                if (isGamePaused()) {
                    waitWhilePaused(generation);
                    continue;
                }
                if (LEGACY_PREVIEW.requiresProjector() && !isActiveProjectorValid()) {
                    break;
                }
                DecodedFrame frame = nextDecodedFrame(decoder);
                if (frame == null) {
                    break;
                }
                frameIndex++;

                int dropped = 0;
                long nowNs = System.nanoTime();
                while (catchUpDropsEnabled && LEGACY_WORKER.isActive(generation)
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
            if (LEGACY_WORKER.isCurrent(generation)) {
                activeNetworkFailure = MediaNetworkFailureClassifier.isNetworkFailure(e);
            }
            LOGGER.error("视频 billboard 预览解码失败", e);
        } catch (Exception e) {
            if (LEGACY_WORKER.isCurrent(generation)) {
                activeNetworkFailure = MediaNetworkFailureClassifier.isNetworkFailure(e);
            }
            LOGGER.error("视频 billboard 预览 native 解码失败", e);
        } finally {
            if (decoder != null) {
                try {
                    decoder.close();
                } catch (Exception e) {
                    LOGGER.warn("视频 billboard 解码器关闭失败", e);
                }
            }
            LEGACY_WORKER.finish(generation, decoder);
        }
    }

    protected static long expectedFrameTimeNs(long frameIndex, long frameIntervalNs) {
        long startNs = activeStartNanoTime;
        return startNs > 0L ? startNs + frameIndex * frameIntervalNs : System.nanoTime();
    }

    protected static boolean isGamePaused() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.isPaused();
    }

    protected static void waitWhilePaused(long generation) {
        long pauseStartNs = System.nanoTime();
        while (LEGACY_WORKER.isActive(generation) && isGamePaused()) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        activeStartNanoTime += Math.max(0L, System.nanoTime() - pauseStartNs);
    }

    protected static void closeActiveDecoderAsync(AutoCloseable decoder) {
        if (decoder == null) {
            return;
        }
        MediaCloseExecutor.closeAsync(decoder, "billboard video decoder");
    }

    static AutoCloseable openDecoder(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, long startOffsetMillis, long totalMillis) throws IOException {
        return openDecoder(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride,
                startOffsetMillis, totalMillis, false);
    }

    static AutoCloseable openDecoder(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, long startOffsetMillis, long totalMillis,
            boolean forceRgbaOutput) throws IOException {
        return openDecoder(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride,
                startOffsetMillis, totalMillis, forceRgbaOutput, DecodeMode.AUTO);
    }

    static AutoCloseable openDecoder(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, long startOffsetMillis, long totalMillis,
            boolean forceRgbaOutput, DecodeMode decodeMode) throws IOException {
        if (com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus.isBusUrl(videoUrl)) {
            String busKey = com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus
                    .keyFromBusUrl(videoUrl);
            IOException last = null;
            String[] requested = decodeMode == DecodeMode.SOFTWARE_ONLY
                    ? new String[] { "none" }
                    : VideoFeatureFlags.requestedHwaccelCandidates();
            for (String hwaccel : requested) {
                if (decodeMode == DecodeMode.HARDWARE_REQUIRED && "none".equalsIgnoreCase(hwaccel)) {
                    continue;
                }
                try {
                    Fmp4NativeVideoDecoder opened = Fmp4NativeVideoDecoder.forLiveBus(
                            busKey, targetWidth, targetHeight,
                            forceRgbaOutput ? Fmp4NativeVideoDecoder.OutputFormat.RGBA : yuvDecodeFormat(), hwaccel,
                            fps);
                    return requireHardwareIfRequested(opened, decodeMode, hwaccel);
                } catch (IOException e) {
                    last = e;
                    LOGGER.warn("直播视频解码器启动失败 hwaccel={}，尝试下一个候选: {}", hwaccel, e.toString());
                }
            }
            throw last != null ? last : new IOException("直播视频解码器不可用");
        }
        if (preferNative) {
            IOException last = null;
            String[] requested = decodeMode == DecodeMode.SOFTWARE_ONLY
                    ? new String[] { "none" }
                    : VideoFeatureFlags.requestedHwaccelCandidates();
            for (String hwaccel : requested) {
                if (decodeMode == DecodeMode.HARDWARE_REQUIRED && "none".equalsIgnoreCase(hwaccel)) {
                    continue;
                }
                try {
                    Fmp4NativeVideoDecoder opened = new Fmp4NativeVideoDecoder(
                            videoUrl, codecId, targetWidth, targetHeight,
                            Integer.MAX_VALUE, true,
                            forceRgbaOutput ? Fmp4NativeVideoDecoder.OutputFormat.RGBA : yuvDecodeFormat(), hwaccel,
                            startOffsetMillis, totalMillis, fps);
                    return requireHardwareIfRequested(opened, decodeMode, hwaccel);
                } catch (IOException e) {
                    last = e;
                    LOGGER.warn("Native 视频解码器启动失败 hwaccel={}，尝试下一个候选: {}", hwaccel, e.toString());
                }
            }
            throw last != null ? last : new IOException("Native video decoder unavailable");
        }
        throw new IOException("视频投影仪不允许使用系统 ffmpeg；请启用/修复内置 native 解码器");
    }

    protected static Fmp4NativeVideoDecoder requireHardwareIfRequested(Fmp4NativeVideoDecoder decoder,
            DecodeMode decodeMode, String requestedHwaccel) throws IOException {
        if (decodeMode != DecodeMode.HARDWARE_REQUIRED) {
            return decoder;
        }
        String actualHwaccel;
        try {
            if (decoder.isHardwareAccelerated()) {
                return decoder;
            }
            actualHwaccel = decoder.actualHwaccel();
        } catch (RuntimeException validationFailure) {
            closeRejectedHardwareDecoder(decoder, "hardware backend validation failed", validationFailure);
            throw validationFailure;
        }
        closeRejectedHardwareDecoder(decoder, "rejected hardware backend close failed", null);
        throw new IOException("候选要求硬件解码但 native backend 未启用硬解: requested="
                + requestedHwaccel + ", actual=" + actualHwaccel);
    }

    protected static void closeRejectedHardwareDecoder(Fmp4NativeVideoDecoder decoder,
            String reason, Throwable validationFailure) {
        CompletableFuture<Void> nativeTermination = decoder.terminationFuture();
        try {
            decoder.close();
        } catch (RuntimeException closeFailure) {
            throw new CandidateResourceCloseException(nativeTermination, reason, closeFailure);
        }
        try {
            nativeTermination.get(3_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CandidateResourceCloseException(nativeTermination,
                    "rejected hardware backend close interrupted", error);
        } catch (java.util.concurrent.ExecutionException error) {
            throw new CandidateResourceCloseException(nativeTermination,
                    "rejected hardware backend close failed", error.getCause());
        } catch (TimeoutException error) {
            throw new CandidateResourceCloseException(nativeTermination,
                    "rejected hardware backend close timed out", error);
        }
        if (validationFailure != null) {
            return;
        }
    }

    static final class CandidateResourceCloseException extends RuntimeException {
        final CompletableFuture<Void> nativeTermination;

        private CandidateResourceCloseException(CompletableFuture<Void> nativeTermination,
                String message, Throwable cause) {
            super(message, cause);
            this.nativeTermination = nativeTermination;
        }
    }

    protected static boolean requestAdaptiveDownscale(int currentWidth, int currentHeight, long generation) {
        PlaybackRequest req = LEGACY_PREVIEW.request();
        if (req == null || !LEGACY_WORKER.isCurrent(generation) || currentWidth <= MIN_ADAPTIVE_WIDTH) {
            return false;
        }
        int nextWidth = Math.max(MIN_ADAPTIVE_WIDTH, Math.round(currentWidth * 0.75F));
        int nextHeight = Math.max(1, Math.round(currentHeight * (nextWidth / (float) currentWidth)));
        long elapsed = req.startOffsetMillis() + Math.max(0L, (System.nanoTime() - req.startedNanoTime()) / 1_000_000L);
        LOGGER.warn("视频上传持续超预算，优先保证游戏流畅：{}x{} -> {}x{}，允许丢帧并重启较低分辨率", currentWidth,
                currentHeight, nextWidth, nextHeight);
        Minecraft.getInstance().execute(() -> {
            VideoBillboardPreview.stopForReplace();
            VideoBillboardPreview.startInternal(req.videoUrl(), nextWidth, nextHeight, req.fps(), req.codecId(), req.preferNative(),
                    req.decoderOverride(), req.sessionId(), elapsed, req.totalMillis(), req.anchorPositions(), true,
                    req.forceRgbaOutput());
        });
        return true;
    }

    protected static void warnNativeOffsetLimitation(AutoCloseable decoder, long startOffsetMillis) {
        if (decoder instanceof Fmp4NativeVideoDecoder && startOffsetMillis > 0L) {
            LOGGER.debug("Native 视频投影使用内置 fMP4 Range seek 起播: offset={}ms", startOffsetMillis);
        }
    }

    static byte[] nextFrame(AutoCloseable decoder) throws Exception {
        try (DecodedFrame frame = nextDecodedFrame(decoder)) {
            if (frame == null) {
                return null;
            }
            if (frame.format() != Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA) {
                throw new IllegalStateException("decoded frame is " + frame.format() + ", not RGBA");
            }
            return frame.data();
        }
    }

    static DecodedFrame nextDecodedFrame(AutoCloseable decoder) throws Exception {
        if (decoder instanceof Fmp4NativeVideoDecoder nativeDecoder) {
            return DecodedFrame.wrap(nativeDecoder.getNextDecodedFrame());
        }
        throw new IOException("unsupported video decoder: " + decoder.getClass().getName());
    }

    static DecodedFrame nextDecodedFrameWithAv1FirstFrameProbe(AutoCloseable decoder) throws Exception {
        if (decoder instanceof Fmp4NativeVideoDecoder nativeDecoder) {
            return DecodedFrame.wrap(nativeDecoder.getNextDecodedFrameWithAv1FirstFrameProbe());
        }
        throw new IOException("unsupported video decoder: " + decoder.getClass().getName());
    }

    static void commitAv1FirstFrameProbe(AutoCloseable decoder, DecodedFrame frame) throws IOException {
        if (decoder instanceof Fmp4NativeVideoDecoder nativeDecoder
                && frame != null
                && frame.delegate instanceof Fmp4NativeVideoDecoder.DecodedFrame nativeFrame) {
            nativeDecoder.commitAv1FirstFrameProbe(nativeFrame);
            return;
        }
        throw new IOException("unsupported video decoder: " + decoder.getClass().getName());
    }

    static void rejectAv1FirstFrameProbeFrame(AutoCloseable decoder, DecodedFrame frame) throws IOException {
        if (decoder instanceof Fmp4NativeVideoDecoder nativeDecoder
                && frame != null
                && frame.delegate instanceof Fmp4NativeVideoDecoder.DecodedFrame nativeFrame) {
            nativeDecoder.rejectAv1FirstFrameProbeFrame(nativeFrame);
            return;
        }
        throw new IOException("unsupported video decoder: " + decoder.getClass().getName());
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

}
