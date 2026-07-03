package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

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
 * GPU 端 YUV/NV12 转 RGB 的 render pipeline 与 RenderType 工厂。
 */
public final class YuvVideoRenderTypes {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier PIPELINE_ID = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "pipeline/yuv420p_entity");
    private static final Identifier TEXTURED_PROBE_PIPELINE_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "pipeline/yuv420p_textured_probe_entity");
    private static final Identifier NV12_PIPELINE_ID = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "pipeline/nv12_entity");
    private static final Identifier FRAGMENT_SHADER = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "core/yuv420p_entity");
    private static final Identifier NV12_FRAGMENT_SHADER = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "core/nv12_entity");
    private static final Identifier TEXTURED_PROBE_FRAGMENT_SHADER = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "core/yuv420p_textured_probe_entity");
    private static final String YUV_SHADER_DEBUG = System.getProperty("ncpb.video.yuv.shader_debug", "")
            .trim().toUpperCase(Locale.ROOT);
    private static final boolean YUV_NO_DEPTH_WRITE = Boolean.getBoolean("ncpb.video.yuv.no_depth_write");

    static final RenderPipeline YUV420P_ENTITY = buildYuv420pEntityPipeline();
    static final RenderPipeline NV12_ENTITY = buildYuvEntityPipeline(NV12_PIPELINE_ID, NV12_FRAGMENT_SHADER);

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
            // 注册前先分配 Iris 程序，避免 YUV/NV12 被误归类到其他 entity pass。
            IrisShaderpackCompat.assignYuvPipelineIfRequested(YUV420P_ENTITY);
            event.registerPipeline(YUV420P_ENTITY);
            IrisShaderpackCompat.assignYuvPipelineIfRequested(NV12_ENTITY);
            event.registerPipeline(NV12_ENTITY);
        }
        // 单采样降级路径：只在禁用三平面 Iris YUV 时注册。
        if (IrisShaderpackCompat.shouldRegisterTexturedProbePipeline()) {
            event.registerPipeline(YUV420P_TEXTURED_PROBE_ENTITY);
        }
    }

    private static RenderPipeline buildYuv420pEntityPipeline() {
        return buildYuvEntityPipeline(PIPELINE_ID, FRAGMENT_SHADER);
    }

    private static RenderPipeline buildYuvEntityPipeline(Identifier pipelineId, Identifier fragmentShader) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                .withLocation(pipelineId)
                .withFragmentShader(fragmentShader)
                .withShaderDefine("NO_OVERLAY")
                .withShaderDefine("ALPHA_CUTOUT", 0.1F)
                .withShaderDefine("PER_FACE_LIGHTING")
                // 视频面片按全亮不透明表面处理，减少光影包重分类风险。
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
            default -> LOGGER.warn("未知 ncpb.video.yuv.shader_debug='{}'，将按正常 YUV→RGB shader 编译", YUV_SHADER_DEBUG);
        }
        return builder.build();
    }

    public static RenderType yuv420pEntity(Identifier yTexture, Identifier uTexture, Identifier vTexture) {
        IrisShaderpackCompat.prepareYuvPipelineForCurrentShaderpackState(YUV420P_ENTITY,
                YUV420P_TEXTURED_PROBE_ENTITY);
        // 不缓存 YUV RenderType：动态纹理变化时重建绑定，避免 Iris 复用过期 sampler。
        // 采样器沿用 Sampler0/1/2，兼容 Iris 对原版 sampler 名的校验。
        return RenderType.create(
                "bili_yuv420p_entity",
                RenderSetup.builder(YUV420P_ENTITY)
                        .withTexture("Sampler0", yTexture)
                        .withTexture("Sampler1", uTexture)
                        .withTexture("Sampler2", vTexture)
                        .createRenderSetup());
    }

    public static RenderType nv12Entity(Identifier yTexture, Identifier uvTexture, Identifier placeholderTexture) {
        IrisShaderpackCompat.prepareYuvPipelineForCurrentShaderpackState(NV12_ENTITY,
                YUV420P_TEXTURED_PROBE_ENTITY);
        return RenderType.create(
                "bili_nv12_entity",
                RenderSetup.builder(NV12_ENTITY)
                        .withTexture("Sampler0", yTexture)
                        .withTexture("Sampler1", uvTexture)
                        .withTexture("Sampler2", placeholderTexture)
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

    public static RenderType videoRgbaEntity(Identifier texture) {
        return RGBA_ENTITY_CACHE.computeIfAbsent(texture, key -> {
            LOGGER.debug("创建视频 RGBA Iris 兼容 RenderType: texture={}, pipeline=ENTITY_SOLID, samplers=Sampler0/1/2",
                    key);
            return RenderType.create(
                    "bili_video_rgba_entity",
                    RenderSetup.builder(RenderPipelines.ENTITY_SOLID)
                            .withTexture("Sampler0", key)
                            // 绑定占位 sampler，避免 Iris 校验 Sampler1/2 时失败。
                            .withTexture("Sampler1", key)
                            .withTexture("Sampler2", key)
                            .useLightmap()
                            .createRenderSetup());
        });
    }

}