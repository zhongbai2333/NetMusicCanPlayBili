package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.api.v0.IrisProgram;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.programs.ShaderKey;

/**
 * Iris API 直接桥接层。
 *
 * <p>
 * 只有在 NeoForge 确认可选 Iris 模组已加载后才会访问此类。把 Iris 相关符号集中放在这里，
 * 可以避免用户未安装 Iris 时，让始终会加载的视频渲染类解析 Iris 类型。
 * </p>
 */
final class IrisDirectCompat {
    private static final IrisApi API = IrisApi.getInstance();

    private IrisDirectCompat() {
    }

    static boolean isShaderPackInUse() {
        return API.isShaderPackInUse();
    }

    static void assignYuvPipeline(RenderPipeline pipeline, String programName) {
        API.assignPipeline(pipeline, IrisProgram.valueOf(programName));
    }

    static void assignYuvPipelineToShaderKey(RenderPipeline pipeline, String shaderKeyName) {
        IrisPipelines.assignPipeline(pipeline, ShaderKey.valueOf(shaderKeyName));
    }
}