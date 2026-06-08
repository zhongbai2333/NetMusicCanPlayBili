package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.renderer.VideoBillboardPreview;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DynamicTexture 上传/渲染诊断工具
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VideoRenderStressBench {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean BENCH_FEATURES_ENABLED = VideoFeatureFlags.benchFeaturesEnabled();
    private static final boolean ENABLED = VideoFeatureFlags.advancedBoolean("bili.video.render_bench", false);
    private static final int TARGET_FPS = VideoFeatureFlags.advancedInt("bili.video.render_bench.fps", 30);
    private static final int[] UPLOAD_FPS_STEPS = parseUploadFpsSteps(
            VideoFeatureFlags.advancedString("bili.video.render_bench.upload_fps_steps", "30,24,15"));
    private static final int FRAMES_PER_STAGE = VideoFeatureFlags.advancedInt("bili.video.render_bench.frames", 120);
    private static final String UPLOAD_MODE = VideoFeatureFlags
            .advancedString("bili.video.render_bench.upload_mode", "rgba,yuv420")
            .trim().toLowerCase(Locale.ROOT);
    private static final double STOP_AVG_UPLOAD_MS = Double.parseDouble(
            VideoFeatureFlags.advancedString("bili.video.render_bench.stop_avg_upload_ms", "45"));
    private static final double STOP_MAX_UPLOAD_MS = Double.parseDouble(
            VideoFeatureFlags.advancedString("bili.video.render_bench.stop_max_upload_ms", "120"));

    private static final Stage[] STAGES = {
            new Stage("480p", 854, 480),
            new Stage("720p", 1280, 720),
            new Stage("1080p", 1920, 1080),
            new Stage("1440p", 2560, 1440),
            new Stage("4K", 3840, 2160),
            new Stage("5K", 5120, 2880),
            new Stage("8K", 7680, 4320)
    };

    private static final AtomicBoolean started = new AtomicBoolean(false);

    private VideoRenderStressBench() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!BENCH_FEATURES_ENABLED) {
            return;
        }
        if (!ENABLED) {
            return;
        }
        if (VideoFeatureFlags.advancedBoolean("bili.video.real_bench", false)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(VideoRenderStressBench::runBench, "bili-video-render-stress-bench");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runBench() {
        LOGGER.info("══════════════════════════════════════════");
        LOGGER.info("  视频渲染/上传压力 Bench 开始");
        LOGGER.info(
                "  gameLoopFps={}, uploadFpsSteps={}, frames/stage={}, uploadMode={}, stopAvgUpload={}ms, stopMaxUpload={}ms",
                TARGET_FPS, java.util.Arrays.toString(UPLOAD_FPS_STEPS), FRAMES_PER_STAGE,
                UPLOAD_MODE, STOP_AVG_UPLOAD_MS, STOP_MAX_UPLOAD_MS);
        LOGGER.info("  路线: CPU 测试帧 → NativeImage 写入 → DynamicTexture.upload → SubmitCustomGeometry");
        LOGGER.info("  模式: rgba=真实 RGBA 全帧上传；yuv420=真实 YUV420P 三平面 RED8 上传，用于验证 YUV+shader 方案收益");
        LOGGER.info("══════════════════════════════════════════");

        try {
            for (int uploadFps : UPLOAD_FPS_STEPS) {
                LOGGER.info("════════════ Upload FPS Strategy: {} fps ════════════", uploadFps);
                for (UploadKind kind : UploadKind.enabledKinds(UPLOAD_MODE)) {
                    LOGGER.info("════════════ Upload Mode: {} ════════════", kind.displayName());
                    for (Stage stage : STAGES) {
                        StageResult result = runStage(stage, uploadFps, kind);
                        logResult(stage, uploadFps, kind, result);
                        if (result.avgUploadMs() >= STOP_AVG_UPLOAD_MS || result.maxUploadMs() >= STOP_MAX_UPLOAD_MS) {
                            LOGGER.warn("  达到停止阈值，终止当前 uploadFps={} mode={} 后续更高分辨率测试。stage={}",
                                    uploadFps, kind.displayName(), stage.name());
                            break;
                        }
                    }
                }
            }
        } finally {
            LOGGER.info("══════════════════════════════════════════");
            LOGGER.info("  视频渲染/上传压力 Bench 结束");
            LOGGER.info("══════════════════════════════════════════");
        }
    }

    private static StageResult runStage(Stage stage, int uploadFps, UploadKind kind) {
        UploadShape uploadShape = kind.uploadShape(stage.width(), stage.height());
        int frameSize = uploadShape.byteCount();
        byte[] frame = new byte[frameSize];
        long frameDelayMs = Math.max(1L, 1000L / Math.max(1, TARGET_FPS));
        int effectiveUploadFps = Math.min(Math.max(1, uploadFps), Math.max(1, TARGET_FPS));
        int uploadCredit = Math.max(1, TARGET_FPS);
        long totalGenerateNs = 0L;
        long totalUploadNs = 0L;
        long maxUploadNs = 0L;
        int loopFrames = 0;
        int uploaded = 0;

        LOGGER.info("─────────────── Stage {} {}x{} @ upload {}fps mode={} uploadTexture={}x{} ───────────────",
                stage.name(), stage.width(), stage.height(), uploadFps, kind.displayName(),
                uploadShape.textureWidth(), uploadShape.textureHeight());
        long stageStartNs = System.nanoTime();
        for (int i = 0; i < FRAMES_PER_STAGE; i++) {
            long frameStartNs = System.nanoTime();
            loopFrames++;
            uploadCredit += effectiveUploadFps;
            if (uploadCredit >= TARGET_FPS) {
                uploadCredit -= TARGET_FPS;
                long genStartNs = System.nanoTime();
                kind.fillFrame(frame, uploadShape, uploaded);
                totalGenerateNs += System.nanoTime() - genStartNs;

                long uploadNs = kind.upload(frame, uploadShape);
                if (uploadNs < 0L) {
                    LOGGER.warn("  Stage {} 提前结束：客户端世界已退出或上传失败", stage.name());
                    break;
                }
                totalUploadNs += uploadNs;
                maxUploadNs = Math.max(maxUploadNs, uploadNs);
                uploaded++;
            }

            if ((i + 1) % Math.max(1, FRAMES_PER_STAGE / 4) == 0) {
                LOGGER.info("  Stage {} 进度: loopFrames={}/{}, uploads={}",
                        stage.name(), i + 1, FRAMES_PER_STAGE, uploaded);
            }

            long elapsedMs = (System.nanoTime() - frameStartNs) / 1_000_000L;
            long sleepMs = frameDelayMs - elapsedMs;
            if (sleepMs > 0L) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        long totalStageNs = System.nanoTime() - stageStartNs;
        return new StageResult(loopFrames, uploaded, totalGenerateNs, totalUploadNs, maxUploadNs, totalStageNs);
    }

    private static void fillBenchFrame(byte[] frame, int width, int height, int frameIndex) {
        int phase = frameIndex * 8;
        for (int y = 0; y < height; y++) {
            int gy = (y * 255) / Math.max(1, height - 1);
            for (int x = 0; x < width; x++) {
                int bar = ((x + phase) * 8 / Math.max(1, width)) & 7;
                int r;
                int g;
                int b;
                switch (bar) {
                    case 0 -> {
                        r = 255;
                        g = gy;
                        b = 0;
                    }
                    case 1 -> {
                        r = 0;
                        g = 255;
                        b = gy;
                    }
                    case 2 -> {
                        r = gy;
                        g = 0;
                        b = 255;
                    }
                    case 3 -> {
                        r = 255;
                        g = 255;
                        b = gy;
                    }
                    case 4 -> {
                        r = 0;
                        g = 255;
                        b = 255;
                    }
                    case 5 -> {
                        r = 255;
                        g = gy;
                        b = 255;
                    }
                    case 6 -> {
                        r = 255;
                        g = 255;
                        b = 255;
                    }
                    default -> {
                        r = gy;
                        g = gy;
                        b = gy;
                    }
                }
                int i = (y * width + x) * 4;
                frame[i] = (byte) r;
                frame[i + 1] = (byte) g;
                frame[i + 2] = (byte) b;
                frame[i + 3] = (byte) 255;
            }
        }
    }

    private static void fillYuv420BenchFrame(byte[] frame, int width, int height, int frameIndex) {
        int ySize = width * height;
        int uvWidth = width / 2;
        int uvHeight = height / 2;
        int uOffset = ySize;
        int vOffset = ySize + uvWidth * uvHeight;
        int phase = frameIndex * 8;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int bar = ((x + phase) * 8 / Math.max(1, width)) & 7;
                frame[y * width + x] = (byte) switch (bar) {
                    case 0 -> 82;
                    case 1 -> 145;
                    case 2 -> 41;
                    case 3 -> 210;
                    case 4 -> 170;
                    case 5 -> 107;
                    case 6 -> 235;
                    default -> 32 + (y * 192 / Math.max(1, height - 1));
                };
            }
        }

        for (int y = 0; y < uvHeight; y++) {
            for (int x = 0; x < uvWidth; x++) {
                int bar = ((x * 2 + phase) * 8 / Math.max(1, width)) & 7;
                int i = y * uvWidth + x;
                int u;
                int v;
                switch (bar) {
                    case 0 -> {
                        u = 90;
                        v = 240;
                    }
                    case 1 -> {
                        u = 54;
                        v = 34;
                    }
                    case 2 -> {
                        u = 240;
                        v = 110;
                    }
                    case 3 -> {
                        u = 16;
                        v = 146;
                    }
                    case 4 -> {
                        u = 166;
                        v = 16;
                    }
                    case 5 -> {
                        u = 202;
                        v = 222;
                    }
                    default -> {
                        u = 128;
                        v = 128;
                    }
                }
                frame[uOffset + i] = (byte) u;
                frame[vOffset + i] = (byte) v;
            }
        }
    }

    private static void logResult(Stage stage, int uploadFps, UploadKind kind, StageResult result) {
        if (result.uploads() <= 0) {
            LOGGER.warn("  Stage {} 无有效帧", stage.name());
            return;
        }
        UploadShape uploadShape = kind.uploadShape(stage.width(), stage.height());
        double rgbaMiBPerFrame = stage.width() * stage.height() * 4.0 / (1024.0 * 1024.0);
        double mibPerFrame = uploadShape.byteCount() / (1024.0 * 1024.0);
        double relativeToRgba = mibPerFrame / Math.max(0.001, rgbaMiBPerFrame) * 100.0;
        double avgGenMs = result.totalGenerateNs() / 1_000_000.0 / result.uploads();
        double avgUploadMs = result.avgUploadMs();
        double maxUploadMs = result.maxUploadMs();
        double actualLoopFps = result.loopFrames() * 1_000_000_000.0 / Math.max(1L, result.totalStageNs());
        double actualUploadFps = result.uploads() * 1_000_000_000.0 / Math.max(1L, result.totalStageNs());
        double uploadBandwidth = mibPerFrame * result.uploads()
                / Math.max(0.001, result.totalUploadNs() / 1_000_000_000.0);
        double amortizedUploadMsPerGameFrame = result.totalUploadNs() / 1_000_000.0 / result.loopFrames();
        LOGGER.info(
                "  Stage {} 结果: mode={}, display={}x{}, uploadTexture={}x{}, {}MiB/frame ({}% of RGBA), uploadFps={}, uploads={}",
                stage.name(), kind.displayName(), stage.width(), stage.height(), uploadShape.textureWidth(),
                uploadShape.textureHeight(), String.format(Locale.ROOT, "%.2f", mibPerFrame),
                String.format(Locale.ROOT, "%.1f", relativeToRgba), uploadFps, result.uploads());
        LOGGER.info(
                "    avgGenerate={}ms, avgUpload={}ms, maxUpload={}ms, amortizedUpload/gameFrame={}ms, actualLoopFps={}, actualUploadFps={}, uploadBandwidth={}MiB/s",
                formatMs(avgGenMs), formatMs(avgUploadMs), formatMs(maxUploadMs),
                formatMs(amortizedUploadMsPerGameFrame),
                String.format(Locale.ROOT, "%.1f", actualLoopFps),
                String.format(Locale.ROOT, "%.1f", actualUploadFps),
                String.format(Locale.ROOT, "%.1f", uploadBandwidth));
    }

    private static int[] parseUploadFpsSteps(String raw) {
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
        return count == 0 ? new int[] { 30 } : java.util.Arrays.copyOf(result, count);
    }

    private static String formatMs(double ms) {
        return String.format(Locale.ROOT, "%.2f", ms);
    }

    private record Stage(String name, int width, int height) {
    }

    private enum UploadKind {
        RGBA("rgba") {
            @Override
            UploadShape uploadShape(int displayWidth, int displayHeight) {
                return new UploadShape(displayWidth, displayHeight, displayWidth * displayHeight * 4);
            }

            @Override
            void fillFrame(byte[] frame, UploadShape shape, int frameIndex) {
                fillBenchFrame(frame, shape.textureWidth(), shape.textureHeight(), frameIndex);
            }

            @Override
            long upload(byte[] frame, UploadShape shape) {
                return VideoBillboardPreview.uploadFrameSyncForBench(frame, shape.textureWidth(),
                        shape.textureHeight());
            }
        },
        YUV420("yuv420") {
            @Override
            UploadShape uploadShape(int displayWidth, int displayHeight) {
                return new UploadShape(displayWidth, displayHeight, displayWidth * displayHeight * 3 / 2);
            }

            @Override
            void fillFrame(byte[] frame, UploadShape shape, int frameIndex) {
                fillYuv420BenchFrame(frame, shape.textureWidth(), shape.textureHeight(), frameIndex);
            }

            @Override
            long upload(byte[] frame, UploadShape shape) {
                return VideoBillboardPreview.uploadYuv420FrameSyncForBench(frame, shape.textureWidth(),
                        shape.textureHeight());
            }
        };

        private final String displayName;

        UploadKind(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }

        abstract UploadShape uploadShape(int displayWidth, int displayHeight);

        abstract void fillFrame(byte[] frame, UploadShape shape, int frameIndex);

        abstract long upload(byte[] frame, UploadShape shape);

        static UploadKind[] enabledKinds(String raw) {
            boolean rgba = raw.contains("rgba") || raw.equals("all");
            boolean yuv = raw.contains("yuv") || raw.contains("nv12") || raw.equals("all");
            if (rgba && yuv) {
                return new UploadKind[] { RGBA, YUV420 };
            }
            if (yuv) {
                return new UploadKind[] { YUV420 };
            }
            return new UploadKind[] { RGBA };
        }
    }

    private record UploadShape(int textureWidth, int textureHeight, int byteCount) {
    }

    private record StageResult(int loopFrames, int uploads, long totalGenerateNs, long totalUploadNs, long maxUploadNs,
            long totalStageNs) {
        double avgUploadMs() {
            return uploads <= 0 ? 0.0 : totalUploadNs / 1_000_000.0 / uploads;
        }

        double maxUploadMs() {
            return maxUploadNs / 1_000_000.0;
        }
    }
}
