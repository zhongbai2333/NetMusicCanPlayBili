package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * GPU 端 YUV420P 转 RGB 的 render pipeline 与 RenderType 工厂。
 */
public final class YuvVideoRenderTypes {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier PIPELINE_ID = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "pipeline/yuv420p_entity");
    private static final Identifier TEXTURED_PROBE_PIPELINE_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "pipeline/yuv420p_textured_probe_entity");
    private static final Identifier FRAGMENT_SHADER = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "core/yuv420p_entity");
    private static final Identifier TEXTURED_PROBE_FRAGMENT_SHADER = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "core/yuv420p_textured_probe_entity");
    private static final String YUV_SHADER_DEBUG = System.getProperty("bili.video.yuv.shader_debug", "")
            .trim().toUpperCase(Locale.ROOT);
    private static final boolean YUV_NO_DEPTH_WRITE = Boolean.getBoolean("bili.video.yuv.no_depth_write");

    static final RenderPipeline YUV420P_ENTITY = buildYuv420pEntityPipeline();

    static final RenderPipeline YUV420P_TEXTURED_PROBE_ENTITY = RenderPipeline.builder(
            RenderPipelines.ENTITY_EMISSIVE_SNIPPET)
            .withLocation(TEXTURED_PROBE_PIPELINE_ID)
            .withFragmentShader(TEXTURED_PROBE_FRAGMENT_SHADER)
            .withShaderDefine("NO_OVERLAY")
            .build();

    private static final Map<Identifier, RenderType> RGBA_ENTITY_CACHE = new ConcurrentHashMap<>();

    private YuvVideoRenderTypes() {
    }

    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        if (!YUV_SHADER_DEBUG.isBlank()) {
            LOGGER.warn("YUV shader 可视化诊断已启用: mode={}。若画面不变，说明当前后端没有执行本模组 YUV fragment shader。",
                    YUV_SHADER_DEBUG);
        }
        if (IrisShaderpackCompat.shouldRegisterThreePlaneYuvPipeline()) {
            // Iris 在 pipeline 注册时会立刻尝试自动分类；先显式分配，避免它先把我们的三平面 YUV
            // pipeline 粗略匹配成其他 entity pass 后再接管错误的 shader 程序。
            IrisShaderpackCompat.assignYuvPipelineIfRequested(YUV420P_ENTITY);
            event.registerPipeline(YUV420P_ENTITY);
        }
        // 单采样兼容降级路径：仅在用户显式禁用三平面 Iris YUV 时注册，避免部分
        // shaderpack 对 Sampler1/2 的校验导致主渲染 pass 失败。
        if (IrisShaderpackCompat.shouldRegisterTexturedProbePipeline()) {
            event.registerPipeline(YUV420P_TEXTURED_PROBE_ENTITY);
        }
    }

    private static RenderPipeline buildYuv420pEntityPipeline() {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                .withLocation(PIPELINE_ID)
                .withFragmentShader(FRAGMENT_SHADER)
                .withShaderDefine("NO_OVERLAY")
                .withShaderDefine("ALPHA_CUTOUT", 0.1F)
                .withShaderDefine("PER_FACE_LIGHTING")
                // 视频四边形本身就是全亮表面。保持此 pass 不透明且写入颜色，避免 shaderpack
                // 意外把它分类成半透明/噪声几何体。
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(YUV_NO_DEPTH_WRITE
                        ? new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)
                        : DepthStencilState.DEFAULT)
                .withCull(false)
                .withSampler("Sampler0")
                .withSampler("Sampler1")
                .withSampler("Sampler2");
        switch (YUV_SHADER_DEBUG) {
            case "CONSTANT", "MAGENTA", "CYAN" -> builder.withShaderDefine("BILI_YUV_DEBUG_CONSTANT");
            case "PLANES", "YUV" -> builder.withShaderDefine("BILI_YUV_DEBUG_PLANES");
            case "Y", "LUMA" -> builder.withShaderDefine("BILI_YUV_DEBUG_Y_ONLY");
            case "U" -> builder.withShaderDefine("BILI_YUV_DEBUG_U_ONLY");
            case "V" -> builder.withShaderDefine("BILI_YUV_DEBUG_V_ONLY");
            case "" -> {
            }
            default -> LOGGER.warn("未知 bili.video.yuv.shader_debug='{}'，将按正常 YUV→RGB shader 编译", YUV_SHADER_DEBUG);
        }
        return builder.build();
    }

    static RenderType yuv420pEntity(Identifier yTexture, Identifier uTexture, Identifier vTexture) {
        IrisShaderpackCompat.prepareYuvPipelineForCurrentShaderpackState(YUV420P_ENTITY,
                YUV420P_TEXTURED_PROBE_ENTITY);
        // 不缓存 YUV RenderType：视频分辨率变化时会重建三张动态 RED8 纹理。Iris shaderpack
        // 激活时，如果复用旧 RenderSetup，可能留下过期 sampler 绑定，并在下一次绘制时因
        // "Missing sampler Sampler1" 崩溃。因此每次提交都重新创建这个很小的 RenderType 包装。
        // sampler 名称保持为 Sampler0/1/2，而不是自定义 SamplerY/U/V：Iris shaderpack 程序分配
        // 可能把此 pipeline 重映射到 TEXTURED/entity 系列程序，Minecraft 会按这些原版 sampler 名
        // 校验绑定。fragment shader 分别把 Sampler0/1/2 当作 Y/U/V 使用。
        return RenderType.create(
                "bili_yuv420p_entity",
                RenderSetup.builder(YUV420P_ENTITY)
                        .withTexture("Sampler0", yTexture)
                        .withTexture("Sampler1", uTexture)
                        .withTexture("Sampler2", vTexture)
                        .createRenderSetup());
    }

    static RenderType yOnlyTexturedProbeEntity(Identifier yTexture) {
        IrisShaderpackCompat.prepareYuvPipelineForCurrentShaderpackState(YUV420P_ENTITY,
                YUV420P_TEXTURED_PROBE_ENTITY);
        if (IrisShaderpackCompat.shouldForceSafeProbeRenderType()) {
            LOGGER.debug("Iris/YUV 单采样兼容降级使用 vanilla ENTITY_SOLID pipeline: texture={}", yTexture);
            return videoRgbaEntity(yTexture);
        }
        return RenderType.create(
                "bili_yuv420p_textured_probe_entity",
                RenderSetup.builder(YUV420P_TEXTURED_PROBE_ENTITY)
                        .withTexture("Sampler0", yTexture)
                        .withTexture("Sampler1", yTexture)
                        .withTexture("Sampler2", yTexture)
                        .createRenderSetup());
    }

    static RenderType videoRgbaEntity(Identifier texture) {
        return RGBA_ENTITY_CACHE.computeIfAbsent(texture, key -> {
            LOGGER.debug("创建视频 RGBA Iris 兼容 RenderType: texture={}, pipeline=ENTITY_SOLID, samplers=Sampler0/1/2",
                    key);
            return RenderType.create(
                    "bili_video_rgba_entity",
                    RenderSetup.builder(RenderPipelines.ENTITY_SOLID)
                            .withTexture("Sampler0", key)
                            // MC26 entity pipeline 会声明 Sampler1/Sampler2。Iris shaderpack 程序可能要求
                            // 每个声明过的 sampler 都有绑定，即使只是简单的 RGBA 视频四边形也一样。
                            // 因此绑定无害占位纹理，并刻意保持这个 RenderType 最小化（无
                            // overlay/outline/crumbling），避免 shaderpack fallback 模式下触发额外 Iris 旁路。
                            .withTexture("Sampler1", key)
                            .withTexture("Sampler2", key)
                            .useLightmap()
                            .createRenderSetup());
        });
    }
}