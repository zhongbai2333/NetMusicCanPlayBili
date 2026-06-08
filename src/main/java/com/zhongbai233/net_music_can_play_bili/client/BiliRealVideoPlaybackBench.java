package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.client.renderer.VideoBillboardPreview;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * B站视频播放链路诊断工具：DASH URL → FFmpeg/native 解码 → DynamicTexture 上传 → 世界 billboard
 */
public final class BiliRealVideoPlaybackBench {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean started = new AtomicBoolean(false);

    private static final boolean BENCH_FEATURES_ENABLED = VideoFeatureFlags.benchFeaturesEnabled();
    private static final boolean ENABLED = VideoFeatureFlags.advancedBoolean("bili.video.real_bench", false);
    private static final String VIDEO_ID = VideoFeatureFlags.advancedString("bili.video.real_bench.bv",
            "BV1qM4y1w716");
    private static final int MAX_OUTPUT_FPS = VideoFeatureFlags.advancedInt("bili.video.real_bench.max_fps", 30);
    private static final int MIN_1080P_FPS = VideoFeatureFlags.advancedInt("bili.video.real_bench.min_1080p_fps", 8);
    private static final int CAP_1080P_FPS = VideoFeatureFlags.advancedInt("bili.video.real_bench.cap_1080p_fps", 10);
    private static final int CAP_4K_FPS = VideoFeatureFlags.advancedInt("bili.video.real_bench.cap_4k_fps", 3);
    private static final int CAP_8K_FPS = VideoFeatureFlags.advancedInt("bili.video.real_bench.cap_8k_fps", 1);
    private static final int FRAMES_PER_STAGE = VideoFeatureFlags.advancedInt("bili.video.real_bench.frames", 120);
    private static final int WARMUP_FRAMES = VideoFeatureFlags.advancedInt("bili.video.real_bench.warmup_frames", 10);
    private static final int[] QUALITY_STEPS = parseIntSteps(
            VideoFeatureFlags.advancedString("bili.video.real_bench.qualities", "16,32,64,80,112,116,120,127"));
    private static final boolean REALTIME_PLAYBACK = Boolean.parseBoolean(
            VideoFeatureFlags.advancedString("bili.video.real_bench.realtime", "true"));
    private static final boolean DECODE_ONLY = Boolean.parseBoolean(
            VideoFeatureFlags.advancedString("bili.video.real_bench.decode_only", "false"));
    private static final boolean DECODE_NO_OUTPUT = Boolean.parseBoolean(
            VideoFeatureFlags.advancedString("bili.video.real_bench.decode_no_output", "false"));
    private static final boolean ADAPTIVE_FPS_CAP = Boolean.parseBoolean(
            VideoFeatureFlags.advancedString("bili.video.real_bench.adaptive_fps_cap", "false"));
    private static final boolean STOP_ON_THRESHOLD = Boolean.parseBoolean(
            VideoFeatureFlags.advancedString("bili.video.real_bench.stop_on_threshold", "false"));
    private static final double STOP_AVG_STAGE_MS = Double.parseDouble(
            VideoFeatureFlags.advancedString("bili.video.real_bench.stop_avg_stage_ms", "45"));
    private static final double STOP_MAX_UPLOAD_MS = Double.parseDouble(
            VideoFeatureFlags.advancedString("bili.video.real_bench.stop_max_upload_ms", "60"));
    private static final boolean START_PREVIEW_AFTER_BENCH = Boolean.parseBoolean(
            VideoFeatureFlags.advancedString("bili.video.real_bench.preview", "true"));
    private static final boolean PREVIEW_PREFER_DEMO = Boolean.parseBoolean(
            VideoFeatureFlags.advancedString("bili.video.real_bench.preview.prefer_demo", "false"));
    private static final boolean PREFER_NATIVE = Boolean.parseBoolean(
            VideoFeatureFlags.advancedString("bili.video.real_bench.native", "true"));
    private static final String NATIVE_HWACCEL = VideoFeatureFlags.advancedString("bili.video.native.hwaccel", "auto")
            .trim();
    private static final String OUTPUT_FORMAT = VideoFeatureFlags
            .advancedString("bili.video.real_bench.output_format", "nv12")
            .trim().toLowerCase(Locale.ROOT);

    private BiliRealVideoPlaybackBench() {
    }

    public static boolean tryStart() {
        if (!BENCH_FEATURES_ENABLED) {
            return false;
        }
        if (!ENABLED) {
            return false;
        }
        if (!started.compareAndSet(false, true)) {
            return true;
        }

        CompletableFuture.runAsync(BiliRealVideoPlaybackBench::runBench)
                .orTimeout(180, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    LOGGER.error("真实视频播放 Bench 超时/失败", e);
                    return null;
                });
        return true;
    }

    private static void runBench() {
        LOGGER.info("══════════════════════════════════════════");
        LOGGER.info("  真实 B站视频播放 Bench 开始");
        LOGGER.info("  videoId={}, maxOutputFps={}, frames/stage={}, warmupFrames={}, qualitySteps={}",
                VIDEO_ID, MAX_OUTPUT_FPS, FRAMES_PER_STAGE, WARMUP_FRAMES, Arrays.toString(QUALITY_STEPS));
        LOGGER.info(
                "  realtimePlayback={}, decodeOnly={}, decodeNoOutput={}, adaptiveFpsCap={}, stopOnThreshold={}, previewPreferDemo={}, preferNative={}",
                REALTIME_PLAYBACK, DECODE_ONLY, DECODE_NO_OUTPUT, ADAPTIVE_FPS_CAP, STOP_ON_THRESHOLD,
                PREVIEW_PREFER_DEMO, PREFER_NATIVE);
        LOGGER.info("  outputFormat={}", OUTPUT_FORMAT);
        LOGGER.info("  画质阶梯说明: 125=HDR、126=杜比视界、129=HDR Vivid，属于特性格式；默认压测只跑分辨率/fps阶梯 {}",
                Arrays.stream(QUALITY_STEPS).mapToObj(BiliApiClient::qualityLabel).toList());
        LOGGER.info("  路线: B站 DASH → {} → GPU texture upload → SubmitCustomGeometry",
                "native FFmpeg JNI " + outputFormatLabel());
        LOGGER.info("══════════════════════════════════════════");

        BiliApiClient.VideoId vid = BiliApiClient.extractVideoId(VIDEO_ID);
        if (vid == null) {
            LOGGER.warn("真实视频 Bench 的 B站视频 ID 无效: {}", VIDEO_ID);
            return;
        }

        BiliApiClient.VideoInfo info;
        try {
            long startMs = System.currentTimeMillis();
            info = BiliApiClient.getVideoInfo(vid);
            LOGGER.info("  视频信息: title='{}', cid={}, duration={}s, page={}/{}, 耗时={}ms",
                    info.displayTitle(), info.cid(), info.duration(), info.page(), info.totalPages(),
                    System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            LOGGER.error("真实视频 Bench 获取视频信息失败", e);
            return;
        }

        StageResult lastUsableResult = null;
        StageResult highestPlayableResult = null;
        StageResult demoResult = null;
        for (int quality : QUALITY_STEPS) {
            StageResult result = runQualityStage(vid, info.cid(), quality);
            if (result == null) {
                continue;
            }
            logResult(result);
            if (isUsableForRequestedMode(result)) {
                lastUsableResult = result;
                if (isSmoothPlaybackResult(result)) {
                    highestPlayableResult = result;
                }
                if (isMuscleDemoResult(result)) {
                    demoResult = result;
                }
            }
            if (STOP_ON_THRESHOLD && shouldStopAfter(result)) {
                LOGGER.warn("  达到停止阈值，终止后续更高质量真实视频测试。quality={}, stream={} {}",
                        quality, result.stream().quality(), result.stream().displaySize());
                break;
            }
        }

        StageResult previewResult = PREVIEW_PREFER_DEMO && demoResult != null ? demoResult
                : highestPlayableResult != null ? highestPlayableResult
                        : lastUsableNonDemoResult(lastUsableResult, demoResult);
        if (!DECODE_ONLY && START_PREVIEW_AFTER_BENCH && previewResult != null) {
            BiliApiClient.VideoStream stream = previewResult.stream();
            int previewFps = previewResult.outputFps();
            LOGGER.info("  Bench 后启动真实视频预览: quality={}, {}, {}, {}, fps={}, mode={}",
                    stream.quality(), stream.displaySize(), stream.codecName(), stream.frameRate(), previewFps,
                    playbackMode(stream));
            Minecraft.getInstance().execute(() -> {
                VideoBillboardPreview.stop();
                VideoBillboardPreview.startBenchPreview(
                        stream.baseUrl(), Math.max(1, stream.width()), Math.max(1, stream.height()), previewFps,
                        stream.codecId(), PREFER_NATIVE, decoderOverrideFor(stream));
            });
        }

        LOGGER.info("══════════════════════════════════════════");
        LOGGER.info("  真实 B站视频播放 Bench 结束");
        LOGGER.info("══════════════════════════════════════════");
    }

    private static StageResult runQualityStage(BiliApiClient.VideoId vid, long cid, int quality) {
        BiliApiClient.VideoStream stream;
        try {
            long startMs = System.currentTimeMillis();
            stream = BiliApiClient.getBestVideoStream(vid, cid, quality);
            LOGGER.info(
                    "─────────────── Real Stage {} → actual {} {} {} {} sourceFps={} outputFps={} mode={} route={} ───────────────",
                    BiliApiClient.qualityLabel(quality), BiliApiClient.qualityLabel(stream.quality()),
                    stream.displaySize(), stream.codecName(), stream.codecs(),
                    stream.frameRate(), outputFpsFor(stream), playbackMode(stream),
                    routeDescription(stream));
            LOGGER.info("  DASH URL 获取完成: urlPrefix={}, 耗时={}ms",
                    stream.baseUrl().substring(0, Math.min(120, stream.baseUrl().length())),
                    System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            LOGGER.error("  Real Stage q{} 获取 DASH 视频流失败", quality, e);
            return null;
        }

        int targetWidth = Math.max(1, stream.width());
        int targetHeight = Math.max(1, stream.height());
        int outputFps = outputFpsFor(stream);
        long totalDecodeNs = 0L;
        long totalQueueWaitNs = 0L;
        long totalNativeGetNs = 0L;
        long totalDecodeOtherNs = 0L;
        long totalUploadNs = 0L;
        long totalLoopNs = 0L;
        long maxDecodeNs = 0L;
        long maxQueueWaitNs = 0L;
        long maxNativeGetNs = 0L;
        long maxDecodeOtherNs = 0L;
        long maxUploadNs = 0L;
        long totalMeasuredFrameBytes = 0L;
        int directFrames = 0;
        int heapFrames = 0;
        int frames = 0;
        int measuredFrames = 0;
        long frameIntervalNs = outputFps > 0 ? 1_000_000_000L / outputFps : 0L;
        int warmupFrames = Math.max(0, Math.min(WARMUP_FRAMES, Math.max(0, FRAMES_PER_STAGE - 1)));
        long stageStartNs = System.nanoTime();

        try (AutoCloseable decoder = openDecoder(stream, targetWidth, targetHeight, outputFps)) {
            for (int i = 0; i < FRAMES_PER_STAGE; i++) {
                long frameStartNs = System.nanoTime();
                long decodeStartNs = System.nanoTime();
                Fmp4NativeVideoDecoder.DecodedFrame frame = nextDecodedFrame(decoder);
                long decodeNs = System.nanoTime() - decodeStartNs;
                if (frame == null) {
                    LOGGER.warn("  Real Stage q{} 提前结束：ffmpeg 输出 EOF，frames={}", quality, frames);
                    break;
                }

                long uploadNs = 0L;
                try {
                    if (!DECODE_ONLY) {
                        uploadNs = VideoBillboardPreview.uploadDecodedFrameSyncForBench(frame, targetWidth,
                                targetHeight);
                        if (uploadNs < 0L) {
                            LOGGER.warn("  Real Stage q{} 提前结束：客户端世界已退出或上传失败", quality);
                            break;
                        }
                    }

                    frames++;

                    if (REALTIME_PLAYBACK && !DECODE_ONLY) {
                        sleepUntilNextFrame(frameStartNs, frameIntervalNs);
                    }

                    long loopNs = System.nanoTime() - frameStartNs;
                    if (frames > warmupFrames) {
                        long queueWaitNs = Math.max(0L, frame.queueWaitNanos());
                        long nativeGetNs = Math.max(0L, frame.nativeGetNanos());
                        long decodeOtherNs = Math.max(0L, decodeNs - queueWaitNs - nativeGetNs);
                        totalDecodeNs += decodeNs;
                        totalQueueWaitNs += queueWaitNs;
                        totalNativeGetNs += nativeGetNs;
                        totalDecodeOtherNs += decodeOtherNs;
                        totalUploadNs += uploadNs;
                        totalLoopNs += loopNs;
                        maxDecodeNs = Math.max(maxDecodeNs, decodeNs);
                        maxQueueWaitNs = Math.max(maxQueueWaitNs, queueWaitNs);
                        maxNativeGetNs = Math.max(maxNativeGetNs, nativeGetNs);
                        maxDecodeOtherNs = Math.max(maxDecodeOtherNs, decodeOtherNs);
                        maxUploadNs = Math.max(maxUploadNs, uploadNs);
                        if (frame.buffer() != null) {
                            directFrames++;
                        } else {
                            heapFrames++;
                        }
                        int decodedFrameBytes = frame.byteLength();
                        totalMeasuredFrameBytes += !DECODE_ONLY
                                ? estimatedUploadBytes(decodedFrameBytes, targetWidth, targetHeight)
                                : decodedFrameBytes;
                        measuredFrames++;
                    }
                } finally {
                    frame.close();
                }

                if ((i + 1) % Math.max(1, FRAMES_PER_STAGE / 4) == 0) {
                    LOGGER.info(
                            "  Real Stage q{} 进度: frames={}/{}, measured={}, avgDecode={}ms(queue={}ms,native={}ms,other={}ms), avgUpload={}ms",
                            quality, i + 1, FRAMES_PER_STAGE, measuredFrames,
                            formatMs(totalDecodeNs / 1_000_000.0 / Math.max(1, measuredFrames)),
                            formatMs(totalQueueWaitNs / 1_000_000.0 / Math.max(1, measuredFrames)),
                            formatMs(totalNativeGetNs / 1_000_000.0 / Math.max(1, measuredFrames)),
                            formatMs(totalDecodeOtherNs / 1_000_000.0 / Math.max(1, measuredFrames)),
                            formatMs(totalUploadNs / 1_000_000.0 / Math.max(1, measuredFrames)));
                }
            }
        } catch (Exception e) {
            LOGGER.error("  Real Stage q{} 解码/上传失败", quality, e);
        }

        long totalStageNs = System.nanoTime() - stageStartNs;
        return new StageResult(quality, stream, outputFps, frames, measuredFrames, warmupFrames, totalDecodeNs,
                totalQueueWaitNs, totalNativeGetNs, totalDecodeOtherNs, totalUploadNs, totalLoopNs, maxDecodeNs,
                maxQueueWaitNs, maxNativeGetNs, maxDecodeOtherNs, maxUploadNs, totalMeasuredFrameBytes,
                totalStageNs, directFrames, heapFrames);
    }

    private static AutoCloseable openDecoder(BiliApiClient.VideoStream stream, int targetWidth, int targetHeight,
            int outputFps) throws Exception {
        LOGGER.info("  使用 native FFmpeg JNI 解码链路: codecId={}, {}x{}, frames={}",
                stream.codecId(), targetWidth, targetHeight, FRAMES_PER_STAGE);
        return new Fmp4NativeVideoDecoder(stream.baseUrl(), stream.codecId(), targetWidth, targetHeight,
                FRAMES_PER_STAGE, !(DECODE_ONLY && DECODE_NO_OUTPUT), outputFormat(), NATIVE_HWACCEL, 0L, 0L,
                outputFps);
    }

    private static Fmp4NativeVideoDecoder.DecodedFrame nextDecodedFrame(AutoCloseable decoder) throws Exception {
        if (decoder instanceof Fmp4NativeVideoDecoder nativeDecoder) {
            return nativeDecoder.getNextDecodedFrame();
        }
        throw new IllegalStateException("unsupported video decoder: " + decoder.getClass().getName());
    }

    private static void logResult(StageResult result) {
        if (result.frames() <= 0) {
            LOGGER.warn("  Real Stage q{} 无有效帧", result.requestedQuality());
            return;
        }
        BiliApiClient.VideoStream stream = result.stream();
        double mibPerFrame = result.avgFrameMiB();
        double rgbaMiBPerFrame = stream.width() * stream.height() * 4.0 / (1024.0 * 1024.0);
        double relativeToRgba = mibPerFrame / Math.max(0.001, rgbaMiBPerFrame) * 100.0;
        LOGGER.info(
                "  Real Stage {} 结果: actual={}, {}, {}, {}, outputFormat={}, upload={}MiB/frame ({}% of RGBA), frames={}, measured={}, warmup={}",
                BiliApiClient.qualityLabel(result.requestedQuality()), BiliApiClient.qualityLabel(stream.quality()),
                stream.displaySize(), stream.codecName(),
                stream.frameRate(), outputFormatLabel(), String.format(Locale.ROOT, "%.2f", mibPerFrame),
                String.format(Locale.ROOT, "%.1f", relativeToRgba), result.frames(),
                result.measuredFrames(), result.warmupFrames());
        LOGGER.info(
                "    measuredAvgDecode={}ms, measuredMaxDecode={}ms, measuredAvgUpload={}ms, measuredMaxUpload={}ms, measuredAvgLoop/frame={}ms, measuredLoopFps={}, uploadBandwidth={}MiB/s, wallLoopFps={}",
                formatMs(result.avgDecodeMs()), formatMs(result.maxDecodeMs()),
                formatMs(result.avgUploadMs()), formatMs(result.maxUploadMs()),
                formatMs(result.avgStageMs()),
                String.format(Locale.ROOT, "%.1f", result.actualLoopFps()),
                String.format(Locale.ROOT, "%.1f", result.uploadBandwidthMiBps()),
                String.format(Locale.ROOT, "%.1f", result.wallLoopFps()));
        LOGGER.info(
                "    decodeBreakdown avg: queueWait={}ms, nativeGetFrame={}ms, other={}ms; max: queueWait={}ms, nativeGetFrame={}ms, other={}ms; frameStorage=direct:{}, heap:{}",
                formatMs(result.avgQueueWaitMs()), formatMs(result.avgNativeGetMs()),
                formatMs(result.avgDecodeOtherMs()), formatMs(result.maxQueueWaitMs()),
                formatMs(result.maxNativeGetMs()), formatMs(result.maxDecodeOtherMs()), result.directFrames(),
                result.heapFrames());
    }

    private static int[] parseIntSteps(String raw) {
        String[] parts = raw.split(",");
        int[] result = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                int value = Integer.parseInt(part.trim());
                if (value > 0) {
                    result[count++] = value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return count == 0 ? new int[] { 16, 32, 64, 80 } : Arrays.copyOf(result, count);
    }

    private static String formatMs(double ms) {
        return String.format(Locale.ROOT, "%.2f", ms);
    }

    private static boolean isYuv420OutputRequested() {
        return OUTPUT_FORMAT.equals("yuv")
                || OUTPUT_FORMAT.equals("yuv420")
                || OUTPUT_FORMAT.equals("yuv420_shader")
                || OUTPUT_FORMAT.equals("yuv420_packed")
                || OUTPUT_FORMAT.equals("packed_yuv420");
    }

    private static boolean isNv12OutputRequested() {
        return OUTPUT_FORMAT.equals("nv12") || OUTPUT_FORMAT.equals("nv12_shader");
    }

    private static boolean isYuvOutputRequested() {
        return isNv12OutputRequested() || isYuv420OutputRequested();
    }

    private static Fmp4NativeVideoDecoder.OutputFormat outputFormat() {
        if (isNv12OutputRequested()) {
            return Fmp4NativeVideoDecoder.OutputFormat.NV12;
        }
        if (isYuv420OutputRequested()) {
            return Fmp4NativeVideoDecoder.OutputFormat.YUV420P;
        }
        return Fmp4NativeVideoDecoder.OutputFormat.RGBA;
    }

    private static boolean isPackedYuvUploadMode() {
        return OUTPUT_FORMAT.equals("yuv420_packed") || OUTPUT_FORMAT.equals("packed_yuv420");
    }

    private static String outputFormatLabel() {
        if (!isYuvOutputRequested()) {
            return "RGBA";
        }
        if (isNv12OutputRequested()) {
            return VideoBillboardPreview.isCustomYuvShaderAvailable()
                    ? "NV12 planes(shader upload, UV=" + (isNv12UvRg8Enabled() ? "RG8" : "RGBA8") + ")"
                    : "NV12→RGBA(cpu/iris-fallback)";
        }
        if (!isPackedYuvUploadMode() && !VideoBillboardPreview.isCustomYuvShaderAvailable()) {
            return "YUV420P→RGBA(cpu/iris-fallback)";
        }
        return isPackedYuvUploadMode() ? "YUV420P packed-upload" : "YUV420P planes(shader upload)";
    }

    private static long estimatedUploadBytes(int decodedFrameBytes, int width, int height) {
        long pixels = (long) Math.max(1, width) * Math.max(1, height);
        if (isNv12OutputRequested()) {
            if (!VideoBillboardPreview.isCustomYuvShaderAvailable()) {
                return pixels * 4L;
            }
            return isNv12UvRg8Enabled() ? pixels * 3L / 2L : pixels * 2L;
        }
        return decodedFrameBytes;
    }

    private static boolean isNv12UvRg8Enabled() {
        return Boolean.parseBoolean(System.getProperty("bili.video.nv12.uv_rg8", "true"));
    }

    private static int outputFpsFor(BiliApiClient.VideoStream stream) {
        int sourceFps = parseFrameRate(stream.frameRate());
        int cap = ADAPTIVE_FPS_CAP ? adaptiveFpsCapFor(stream) : MAX_OUTPUT_FPS;
        if (sourceFps <= 0) {
            return Math.max(1, Math.min(MAX_OUTPUT_FPS, cap));
        }
        return Math.max(1, Math.min(Math.min(sourceFps, MAX_OUTPUT_FPS), cap));
    }

    private static void sleepUntilNextFrame(long frameStartNs, long frameIntervalNs) {
        if (frameIntervalNs <= 0L) {
            return;
        }
        long remainingNs = frameIntervalNs - (System.nanoTime() - frameStartNs);
        if (remainingNs <= 0L) {
            return;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(remainingNs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int adaptiveFpsCapFor(BiliApiClient.VideoStream stream) {
        long pixels = (long) Math.max(1, stream.width()) * Math.max(1, stream.height());
        if (pixels >= 7000L * 4000L) {
            return Math.max(1, CAP_8K_FPS);
        }
        if (pixels >= 3000L * 1600L) {
            return Math.max(1, CAP_4K_FPS);
        }
        if (pixels >= 1800L * 1000L) {
            return Math.max(1, CAP_1080P_FPS);
        }
        return Math.max(1, MAX_OUTPUT_FPS);
    }

    private static boolean isUsableForRequestedMode(StageResult result) {
        if (result.frames() <= 0) {
            return false;
        }
        if (isMuscleDemoResult(result)) {
            return result.actualLoopFps() >= Math.max(0.25, result.outputFps() * 0.5);
        }
        if (isAtLeast1080p(result.stream())) {
            return result.actualLoopFps() >= Math.min(MIN_1080P_FPS, result.outputFps() * 0.75);
        }
        return isSmoothPlaybackResult(result);
    }

    private static boolean isSmoothPlaybackResult(StageResult result) {
        return result.frames() > 0
                && result.actualLoopFps() >= Math.min(result.outputFps() * 0.75, result.outputFps() - 1.0);
    }

    private static StageResult lastUsableNonDemoResult(StageResult lastUsableResult, StageResult demoResult) {
        if (lastUsableResult != null && !isMuscleDemoResult(lastUsableResult)) {
            return lastUsableResult;
        }
        LOGGER.warn("  Bench 后预览跳过 4K/8K muscle demo，避免高分辨率上传拖慢游戏 FPS。"
                + "如需强行预览，请设置 -Dbili.video.real_bench.preview.prefer_demo=true");
        return null;
    }

    private static boolean isMuscleDemoResult(StageResult result) {
        BiliApiClient.VideoStream stream = result.stream();
        long pixels = (long) Math.max(1, stream.width()) * Math.max(1, stream.height());
        return pixels >= 3000L * 1600L;
    }

    private static boolean isAtLeast1080p(BiliApiClient.VideoStream stream) {
        long pixels = (long) Math.max(1, stream.width()) * Math.max(1, stream.height());
        return pixels >= 1800L * 1000L;
    }

    private static boolean shouldStopAfter(StageResult result) {
        if (result.maxUploadMs() >= STOP_MAX_UPLOAD_MS) {
            return true;
        }
        if (isMuscleDemoResult(result)) {
            return false;
        }
        return result.avgStageMs() >= STOP_AVG_STAGE_MS;
    }

    private static String playbackMode(BiliApiClient.VideoStream stream) {
        long pixels = (long) Math.max(1, stream.width()) * Math.max(1, stream.height());
        if (pixels >= 7000L * 4000L) {
            return "8K muscle demo";
        }
        if (pixels >= 3000L * 1600L) {
            return "4K+ muscle demo";
        }
        if (isAtLeast1080p(stream)) {
            return "1080p guaranteed low-fps playback";
        }
        return "realtime playback";
    }

    private static String routeDescription(BiliApiClient.VideoStream stream) {
        return "native(requestedHwaccel=" + (NATIVE_HWACCEL.isBlank() ? "auto" : NATIVE_HWACCEL) + ")";
    }

    private static String decoderOverrideFor(BiliApiClient.VideoStream stream) {
        String configured = System.getProperty("bili.video.ffmpeg.decoder", "").trim();
        if (configured.isBlank()) {
            return null;
        }
        String lower = configured.toLowerCase(Locale.ROOT);
        if (stream.codecId() == 7 && (lower.contains("264") || lower.contains("avc"))) {
            return configured;
        }
        if (stream.codecId() == 12 && (lower.contains("265") || lower.contains("hevc"))) {
            return configured;
        }
        return null;
    }

    private static int parseFrameRate(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String normalized = raw.trim();
        try {
            if (normalized.contains("/")) {
                String[] parts = normalized.split("/", 2);
                double numerator = Double.parseDouble(parts[0].trim());
                double denominator = Double.parseDouble(parts[1].trim());
                if (denominator > 0.0) {
                    return Math.max(1, (int) Math.round(numerator / denominator));
                }
            }
            return Math.max(1, (int) Math.round(Double.parseDouble(normalized)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record StageResult(int requestedQuality, BiliApiClient.VideoStream stream, int outputFps, int frames,
            int measuredFrames, int warmupFrames, long totalDecodeNs, long totalQueueWaitNs, long totalNativeGetNs,
            long totalDecodeOtherNs, long totalUploadNs, long totalLoopNs, long maxDecodeNs, long maxQueueWaitNs,
            long maxNativeGetNs, long maxDecodeOtherNs, long maxUploadNs, long totalMeasuredFrameBytes,
            long totalStageNs, int directFrames, int heapFrames) {
        double avgDecodeMs() {
            return measuredFrames <= 0 ? 0.0 : totalDecodeNs / 1_000_000.0 / measuredFrames;
        }

        double avgQueueWaitMs() {
            return measuredFrames <= 0 ? 0.0 : totalQueueWaitNs / 1_000_000.0 / measuredFrames;
        }

        double avgNativeGetMs() {
            return measuredFrames <= 0 ? 0.0 : totalNativeGetNs / 1_000_000.0 / measuredFrames;
        }

        double avgDecodeOtherMs() {
            return measuredFrames <= 0 ? 0.0 : totalDecodeOtherNs / 1_000_000.0 / measuredFrames;
        }

        double avgUploadMs() {
            return measuredFrames <= 0 ? 0.0 : totalUploadNs / 1_000_000.0 / measuredFrames;
        }

        double maxDecodeMs() {
            return maxDecodeNs / 1_000_000.0;
        }

        double maxQueueWaitMs() {
            return maxQueueWaitNs / 1_000_000.0;
        }

        double maxNativeGetMs() {
            return maxNativeGetNs / 1_000_000.0;
        }

        double maxDecodeOtherMs() {
            return maxDecodeOtherNs / 1_000_000.0;
        }

        double maxUploadMs() {
            return maxUploadNs / 1_000_000.0;
        }

        double avgStageMs() {
            return measuredFrames <= 0 ? 0.0 : totalLoopNs / 1_000_000.0 / measuredFrames;
        }

        double actualLoopFps() {
            return measuredFrames * 1_000_000_000.0 / Math.max(1L, totalLoopNs);
        }

        double wallLoopFps() {
            return frames * 1_000_000_000.0 / Math.max(1L, totalStageNs);
        }

        double avgFrameMiB() {
            if (measuredFrames > 0 && totalMeasuredFrameBytes > 0) {
                return totalMeasuredFrameBytes / 1024.0 / 1024.0 / measuredFrames;
            }
            return stream.width() * stream.height() * 4.0 / (1024.0 * 1024.0);
        }

        double uploadBandwidthMiBps() {
            double mibPerFrame = avgFrameMiB();
            return mibPerFrame * measuredFrames / Math.max(0.001, totalUploadNs / 1_000_000_000.0);
        }
    }
}
