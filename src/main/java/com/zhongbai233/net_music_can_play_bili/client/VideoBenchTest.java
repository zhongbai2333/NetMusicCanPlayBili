package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.client.renderer.VideoBillboardPreview;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 视频解码 bench 测试 — 在游戏启动后自动运行一次。
 *
 * 测试内容:
 * 1. 通过内置 native 解码 B站视频流
 * 2. 测量解码性能
 * 3. 分析首帧 RGBA 数据
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VideoBenchTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean BENCH_FEATURES_ENABLED = VideoFeatureFlags.benchFeaturesEnabled();
    private static final AtomicBoolean hasRun = new AtomicBoolean(false);
    private static final int TARGET_W = 854;
    private static final int TARGET_H = 480;
    private static final int TARGET_FPS = 30;
    private static final int MAX_FRAMES = 60;
    private static final boolean CPU_BARS = VideoFeatureFlags.advancedBoolean("bili.video.cpu_bars", false);
    private static final boolean RUN_DECODE_BENCH = VideoFeatureFlags.advancedBoolean("bili.video.run_decode_bench",
            false);
    private static final int PREFERRED_QUALITY = VideoFeatureFlags.advancedInt("bili.video.preferred_quality", 16);

    private VideoBenchTest() {
    }

    /**
     * 在客户端 tick 中调用（仅首次运行）。
     * 异步执行解码测试，不阻塞游戏主循环。
     */
    public static void tryRunOnce(String testVideoUrl) {
        if (testVideoUrl == null || testVideoUrl.isBlank())
            return;

        LOGGER.info("══════════════════════════════════════════");
        LOGGER.info("  视频解码 Bench 测试开始");
        LOGGER.info("  目标: {}×{} @ {}fps, 解码 {} 帧", TARGET_W, TARGET_H, TARGET_FPS, MAX_FRAMES);
        LOGGER.info("  视频: {}", testVideoUrl.substring(0, Math.min(80, testVideoUrl.length())));
        LOGGER.info("══════════════════════════════════════════");

        long startMs = System.currentTimeMillis();

        try (Fmp4NativeVideoDecoder dec = new Fmp4NativeVideoDecoder(
                testVideoUrl, 7, TARGET_W, TARGET_H, MAX_FRAMES)) {

            int frameCount = 0;
            long firstFrameMs = 0;
            byte[] firstFrame = null;

            for (int i = 0; i < MAX_FRAMES; i++) {
                byte[] rgba = dec.getNextFrame();
                if (rgba == null)
                    break;

                if (frameCount == 0) {
                    firstFrameMs = System.currentTimeMillis() - startMs;
                    firstFrame = rgba;
                }
                frameCount++;
            }

            long totalMs = System.currentTimeMillis() - startMs;
            double avgMsPerFrame = frameCount > 0 ? (double) totalMs / frameCount : 0;
            double effectiveFps = avgMsPerFrame > 0 ? 1000.0 / avgMsPerFrame : 0;

            LOGGER.info("─────────────── Bench 结果 ───────────────");
            LOGGER.info("  解码帧数:     {}", frameCount);
            LOGGER.info("  首帧延迟:     {}ms", firstFrameMs);
            LOGGER.info("  总耗时:       {}ms", totalMs);
            LOGGER.info("  平均每帧:     {}ms", String.format("%.1f", avgMsPerFrame));
            LOGGER.info("  等效 FPS:     {}", String.format("%.1f", effectiveFps));
            LOGGER.info("  帧大小:       {}×{} = {}B/帧",
                    TARGET_W, TARGET_H, TARGET_W * TARGET_H * 4);

            if (firstFrame != null) {
                analyzeFirstFrame(firstFrame);
            }

            LOGGER.info("  评估: {} {}",
                    effectiveFps >= TARGET_FPS ? "✅" : "⚠️",
                    effectiveFps >= TARGET_FPS
                            ? "解码性能充足，可支持实时播放"
                            : "解码性能不足，需降低分辨率或帧率");

            Minecraft.getInstance().execute(() -> VideoBillboardPreview.start(
                    testVideoUrl, TARGET_W, TARGET_H, TARGET_FPS));

        } catch (Exception e) {
            LOGGER.error("视频解码测试失败: {}", e.getMessage(), e);
            LOGGER.warn("B站视频片段下载/解码失败，改用本地 FFmpeg testsrc2 彩条验证 MC26.1.2 渲染链路");
            Minecraft.getInstance().execute(() -> VideoBillboardPreview.startTestPattern(
                    TARGET_W, TARGET_H, TARGET_FPS));
        }

        LOGGER.info("══════════════════════════════════════════");
        LOGGER.info("  解码完成。渲染待接 — MC26.1 Core Profile 需走 Blaze3D API");
    }

    private static void analyzeFirstFrame(byte[] rgba) {
        int pixels = TARGET_W * TARGET_H;
        if (rgba.length != pixels * 4) {
            LOGGER.warn("  帧大小不匹配: 期望 {}B, 实际 {}B", pixels * 4, rgba.length);
            return;
        }

        // 统计颜色分布（top-left 3×3 像素）
        StringBuilder sb = new StringBuilder("  首帧左上角 3×3 RGBA:\n");
        for (int y = 0; y < 3; y++) {
            sb.append("    ");
            for (int x = 0; x < 3; x++) {
                int i = (y * TARGET_W + x) * 4;
                int r = rgba[i] & 0xFF;
                int g = rgba[i + 1] & 0xFF;
                int b = rgba[i + 2] & 0xFF;
                sb.append(String.format("#%02X%02X%02X ", r, g, b));
            }
            sb.append("\n");
        }
        LOGGER.info(sb.toString());

        // 统计颜色分布
        int[] colorCounts = new int[256 * 256 * 256];
        int uniqueColors = 0;
        try {
            for (int i = 0; i < pixels * 4; i += 4) {
                int r = rgba[i] & 0xFF;
                int g = rgba[i + 1] & 0xFF;
                int b = rgba[i + 2] & 0xFF;
                int idx = (r << 16) | (g << 8) | b;
                if (colorCounts[idx] == 0)
                    uniqueColors++;
                colorCounts[idx]++;
            }
            LOGGER.info("  唯一颜色数:   {} / {} ({}%)",
                    uniqueColors, pixels, String.format("%.0f", uniqueColors * 100.0 / pixels));
        } catch (ArrayIndexOutOfBoundsException ignored) {
            // 颜色空间太大用简单统计
            LOGGER.info("  唯一颜色数:   (未统计 — 需要更高效算法)");
        }

        // 统计平均亮度
        long totalGray = 0;
        for (int i = 0; i < pixels * 4; i += 4) {
            int r = rgba[i] & 0xFF;
            int g = rgba[i + 1] & 0xFF;
            int b = rgba[i + 2] & 0xFF;
            totalGray += (r * 30 + g * 59 + b * 11) / 100; // 感知亮度
        }
        int avgGray = (int) (totalGray / pixels);
        LOGGER.info("  平均亮度:     {}", avgGray);
    }

    // ── 客户端 tick 触发器 ──

    /**
     * 在客户端首次 tick 时自动运行 bench。
     * B站视频 ID 通过系统属性传入: -Dbili.video.bench=BV1GJ411x7h7
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!BENCH_FEATURES_ENABLED) {
            return;
        }
        // 只在进入世界后才触发
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;

        if (BiliRealVideoPlaybackBench.tryStart()) {
            return;
        }

        String benchVideoId = System.getProperty("bili.video.bench", "");
        if (benchVideoId.isBlank() && !CPU_BARS)
            return;

        if (CPU_BARS) {
            if (hasRun.compareAndSet(false, true)) {
                LOGGER.info("bili.video.cpu_bars=true，跳过 B站解析/下载，直接启动 CPU 彩条渲染诊断");
                VideoBillboardPreview.startTestPattern(TARGET_W, TARGET_H, TARGET_FPS);
            }
            return;
        }

        tryRunOnceBili(benchVideoId);
    }

    private static void tryRunOnceBili(String videoId) {
        if (!hasRun.compareAndSet(false, true))
            return;

        // 解析 B站视频 ID → 获取 DASH 视频流 URL
        BiliApiClient.VideoId vid = BiliApiClient.extractVideoId(videoId);
        if (vid == null) {
            LOGGER.warn("无效的 B站视频 ID: {}", videoId);
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                long start = System.currentTimeMillis();
                LOGGER.info("[视频预览] 阶段 1/4: 开始解析 B站视频信息 id={}", vid.asInputText());
                BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(vid);
                LOGGER.info("[视频预览] 阶段 1/4 完成: title='{}', cid={}, duration={}s, 耗时={}ms",
                        info.displayTitle(), info.cid(), info.duration(), System.currentTimeMillis() - start);

                long playurlStart = System.currentTimeMillis();
                LOGGER.info("[视频预览] 阶段 2/4: 开始获取 DASH 视频流 URL (preferredQuality={})",
                        PREFERRED_QUALITY);
                String videoUrl = BiliApiClient.getBestVideoUrl(vid, info.cid(), PREFERRED_QUALITY);
                LOGGER.info("[视频预览] 阶段 2/4 完成: urlPrefix={}, 耗时={}ms",
                        videoUrl.substring(0, Math.min(120, videoUrl.length())),
                        System.currentTimeMillis() - playurlStart);

                if (RUN_DECODE_BENCH) {
                    LOGGER.info("[视频预览] 阶段 3/4: 开始 bench 下载/解码");
                    tryRunOnce(videoUrl);
                } else {
                    LOGGER.info("[视频预览] 跳过 bench，直接启动实时 billboard 预览；如需 bench 请加 -Dbili.video.run_decode_bench=true");
                    Minecraft.getInstance().execute(() -> VideoBillboardPreview.start(
                            videoUrl, TARGET_W, TARGET_H, TARGET_FPS));
                }
            } catch (Exception e) {
                LOGGER.error("获取 B站视频流失败", e);
                LOGGER.warn("B站解析链路失败，启动 CPU 彩条以保持渲染诊断可见");
                Minecraft.getInstance().execute(() -> VideoBillboardPreview.startTestPattern(
                        TARGET_W, TARGET_H, TARGET_FPS));
            }
        }).orTimeout(90, TimeUnit.SECONDS).exceptionally(e -> {
            LOGGER.error("B站视频解析/下载链路超过 90 秒未完成，判定卡住", e);
            Minecraft.getInstance().execute(() -> VideoBillboardPreview.startTestPattern(
                    TARGET_W, TARGET_H, TARGET_FPS));
            return null;
        });
    }
}
