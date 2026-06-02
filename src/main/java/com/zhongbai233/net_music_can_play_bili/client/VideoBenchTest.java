package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.codec.FfmpegSubprocessDecoder;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 视频解码 bench 测试 — 在游戏启动后自动运行一次。
 *
 * 测试内容:
 * 1. 通过系统 ffmpeg 解码 B站视频流
 * 2. 测量解码性能
 * 3. 分析首帧 RGBA 数据
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VideoBenchTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean hasRun = new AtomicBoolean(false);
    private static final int TARGET_W = 40;
    private static final int TARGET_H = 22;
    private static final int TARGET_FPS = 20;
    private static final int MAX_FRAMES = 60;

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

        try (FfmpegSubprocessDecoder dec = new FfmpegSubprocessDecoder(
                testVideoUrl, TARGET_W, TARGET_H, TARGET_FPS)) {

            int frameCount = 0;
            long firstFrameMs = 0;
            byte[] firstFrame = null;

            for (int i = 0; i < MAX_FRAMES; i++) {
                byte[] rgba = dec.getNextFrame();
                if (rgba == null) break;

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

        } catch (Exception e) {
            LOGGER.error("视频解码测试失败: {}", e.getMessage(), e);
        }

        LOGGER.info("══════════════════════════════════════════");

        // 启动世界内视频渲染
        LOGGER.info("🎬 启动世界内视频渲染...");
        VideoScreenRenderer.startPlayback(testVideoUrl);
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
                if (colorCounts[idx] == 0) uniqueColors++;
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
            totalGray += (r * 30 + g * 59 + b * 11) / 100;  // 感知亮度
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
        String benchVideoId = System.getProperty("bili.video.bench", "");
        if (benchVideoId.isBlank())
            return;

        // 只在进入世界后才触发
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;

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
                BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(vid);
                String videoUrl = BiliApiClient.getBestVideoUrl(vid, info.cid(), 32);
                LOGGER.info("获取视频流 URL: {}", videoUrl.substring(0, Math.min(80, videoUrl.length())));
                tryRunOnce(videoUrl);
            } catch (Exception e) {
                LOGGER.error("获取 B站视频流失败", e);
            }
        });
    }
}
