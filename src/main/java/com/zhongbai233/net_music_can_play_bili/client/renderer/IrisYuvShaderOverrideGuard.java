package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 保证本模组的 YUV fragment shader 留在自己的 pipeline 上，而不是让 Iris shaderpack
 * 替换编译后的程序。所有检查都限定在 {@link #YUV_PIPELINE_ID}；这里不会修改 Iris 全局状态。
 */
public final class IrisYuvShaderOverrideGuard {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier YUV_PIPELINE_ID = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "pipeline/yuv420p_entity");
    private static final Identifier NV12_PIPELINE_ID = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "pipeline/nv12_entity");
    private static final AtomicBoolean LOGGED_BYPASS = new AtomicBoolean(false);

    private IrisYuvShaderOverrideGuard() {
    }

    public static boolean isYuvPipeline(RenderPipeline pipeline) {
        return IrisShaderpackCompat.isYuvShaderpackBypassEnabled()
                && IrisShaderpackCompat.shouldApplyIrisYuvCompatibility()
                && isYuvPipelineId(pipeline);
    }

    public static boolean isYuvPipelineId(RenderPipeline pipeline) {
        return pipeline != null && (YUV_PIPELINE_ID.equals(pipeline.getLocation())
                || NV12_PIPELINE_ID.equals(pipeline.getLocation()));
    }

    public static void logBypassedShaderpackPipelineOnce() {
        if (LOGGED_BYPASS.compareAndSet(false, true)) {
            LOGGER.warn("Iris/YUV: 已对本模组 YUV/NV12 pipeline 旁路 Iris shaderpack program 替换，改用原始 GlProgram");
        }
    }

}