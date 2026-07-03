package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 轻量视频测试启动器
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VideoBenchTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean BENCH_FEATURES_ENABLED = VideoFeatureFlags.benchFeaturesEnabled();
    private static final AtomicBoolean hasRun = new AtomicBoolean(false);
    private static final int TARGET_W = 854;
    private static final int TARGET_H = 480;
    private static final int TARGET_FPS = 30;
    private static final boolean CPU_BARS = VideoFeatureFlags.advancedBoolean("ncpb.video.cpu_bars", false);

    private VideoBenchTest() {
    }

    // ── 客户端 tick 触发器 ──

    /** 在客户端首次 tick 时自动运行保留的 Bench/诊断入口。 */
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

        if (!CPU_BARS) {
            return;
        }

        if (hasRun.compareAndSet(false, true)) {
            LOGGER.info("ncpb.video.cpu_bars=true，跳过 B站解析/下载，直接启动 CPU 彩条渲染诊断");
            VideoBillboardPreview.startTestPattern(TARGET_W, TARGET_H, TARGET_FPS);
        }
    }
}
