package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private static final Identifier RGBA_FLAT_SOLID_PIPELINE_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "pipeline/video_rgba_flat_solid");
    private static final Identifier RGBA_FLAT_CUTOUT_PIPELINE_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "pipeline/video_rgba_flat_cutout");
    private static final Identifier RGBA_FLAT_TRANSLUCENT_PIPELINE_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "pipeline/video_rgba_flat_translucent");
    private static final Identifier FRAGMENT_SHADER = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "core/yuv420p_entity");
    private static final Identifier NV12_FRAGMENT_SHADER = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "core/nv12_entity");
    private static final Identifier TEXTURED_PROBE_FRAGMENT_SHADER = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "core/yuv420p_textured_probe_entity");
    private static final String YUV_SHADER_DEBUG = VideoPipelineProperties.yuv().shaderDebug();
        private static final boolean YUV_NO_DEPTH_WRITE = VideoYuvRenderPolicy.disableDepthWrite();

    static final RenderPipeline YUV420P_ENTITY = buildYuv420pEntityPipeline();
    static final RenderPipeline NV12_ENTITY = buildYuvEntityPipeline(NV12_PIPELINE_ID, NV12_FRAGMENT_SHADER);
    static final RenderPipeline RGBA_FLAT_SOLID = buildFlatRgbaPipeline(
            RGBA_FLAT_SOLID_PIPELINE_ID, VideoRgbaRenderPass.SOLID);
    static final RenderPipeline RGBA_FLAT_CUTOUT = buildFlatRgbaPipeline(
            RGBA_FLAT_CUTOUT_PIPELINE_ID, VideoRgbaRenderPass.CUTOUT);
    static final RenderPipeline RGBA_FLAT_TRANSLUCENT = buildFlatRgbaPipeline(
            RGBA_FLAT_TRANSLUCENT_PIPELINE_ID, VideoRgbaRenderPass.TRANSLUCENT);

    static final RenderPipeline YUV420P_TEXTURED_PROBE_ENTITY = RenderPipeline.builder(
            RenderPipelines.ENTITY_EMISSIVE_SNIPPET)
            .withLocation(TEXTURED_PROBE_PIPELINE_ID)
            .withFragmentShader(TEXTURED_PROBE_FRAGMENT_SHADER)
            .withShaderDefine("NO_OVERLAY")
            .withShaderDefine("NO_CARDINAL_LIGHTING")
            .build();

        private static final int MAX_RGBA_ENTITY_CACHE_ENTRIES = 64;
        private static final Map<Identifier, RenderType> RGBA_ENTITY_CACHE = newRgbaRenderTypeCache();
        private static final Map<Identifier, RenderType> RGBA_CUTOUT_ENTITY_CACHE = newRgbaRenderTypeCache();
        private static final Map<Identifier, RenderType> RGBA_TRANSLUCENT_ENTITY_CACHE = newRgbaRenderTypeCache();

        private static Map<Identifier, RenderType> newRgbaRenderTypeCache() {
                return Collections.synchronizedMap(
                        new LinkedHashMap<>(MAX_RGBA_ENTITY_CACHE_ENTRIES, 0.75F, true) {
                                @Override
                                protected boolean removeEldestEntry(Map.Entry<Identifier, RenderType> eldest) {
                                        return size() > MAX_RGBA_ENTITY_CACHE_ENTRIES;
                                }
                        });
        }

    private YuvVideoRenderTypes() {
    }

    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(RGBA_FLAT_SOLID);
        event.registerPipeline(RGBA_FLAT_CUTOUT);
        event.registerPipeline(RGBA_FLAT_TRANSLUCENT);
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
            registerOit(event, YUV420P_OIT);
            registerOit(event, NV12_OIT);
        }
        // 单采样降级路径：只在禁用三平面 Iris YUV 时注册。
        if (IrisShaderpackCompat.shouldRegisterTexturedProbePipeline()) {
            event.registerPipeline(YUV420P_TEXTURED_PROBE_ENTITY);
        }
    }

    private static final OitPipelineSet YUV420P_OIT = buildYuvOit("yuv420p_entity", FRAGMENT_SHADER);
    private static final OitPipelineSet NV12_OIT = buildYuvOit("nv12_entity", NV12_FRAGMENT_SHADER);

    private static OitPipelineSet buildYuvOit(String name, Identifier fragmentShader) {
        return OitPipelineSet.builder("ncpb_" + name, yuvBuilder(fragmentShader))
                .withDepthBoundsModifier(builder -> builder.withLocation(
                        Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, "pipeline/oit_depth_bounds_" + name)))
                .withTransmittanceModifier(builder -> builder.withLocation(
                        Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, "pipeline/oit_transmittance_" + name)))
                .withAccumulateModifier(builder -> builder.withLocation(
                        Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, "pipeline/oit_accumulate_" + name)))
                .build();
    }

    private static void registerOit(RegisterRenderPipelinesEvent event, OitPipelineSet pipelines) {
        event.registerPipeline(pipelines.depthBoundsPipeline());
        event.registerPipeline(pipelines.transmittancePipeline());
        event.registerPipeline(pipelines.accumulatePipeline());
    }

    private static RenderPipeline buildYuv420pEntityPipeline() {
        return buildYuvEntityPipeline(PIPELINE_ID, FRAGMENT_SHADER);
    }

    private static RenderPipeline buildFlatRgbaPipeline(Identifier pipelineId, VideoRgbaRenderPass renderPass) {
        if (renderPass == VideoRgbaRenderPass.SKIP) {
            throw new IllegalArgumentException("SKIP does not have a render pipeline");
        }
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                .withLocation(pipelineId)
                .withShaderDefine("NO_OVERLAY")
                .withShaderDefine("NO_CARDINAL_LIGHTING")
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL,
                        renderPass.depthWrite()))
                .withBindGroupLayout(BindGroupLayouts.SAMPLER1);
        if (renderPass.alphaCutout() > 0.0F) {
            builder.withShaderDefine("ALPHA_CUTOUT", renderPass.alphaCutout());
        }
        if (renderPass.blending()) {
            builder.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withCull(false);
        }
        return builder.build();
    }

    private static RenderPipeline buildYuvEntityPipeline(Identifier pipelineId, Identifier fragmentShader) {
        return yuvBuilder(fragmentShader).withLocation(pipelineId)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withDepthStencilState(YUV_NO_DEPTH_WRITE
                        ? new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)
                        : DepthStencilState.DEFAULT).build();
    }

    private static RenderPipeline.Builder yuvBuilder(Identifier fragmentShader) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.ENTITY_EMISSIVE_SNIPPET)
                .withFragmentShader(fragmentShader)
                .withShaderDefine("NO_OVERLAY")
                .withShaderDefine("NO_CARDINAL_LIGHTING")
                .withCull(false)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER2);
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
        return builder;
    }

    public static RenderType yuv420pEntity(Identifier yTexture, Identifier uTexture, Identifier vTexture) {
        IrisShaderpackCompat.prepareYuvPipelineForCurrentShaderpackState(YUV420P_ENTITY,
                YUV420P_TEXTURED_PROBE_ENTITY);
        // 不缓存 YUV RenderType：动态纹理变化时重建绑定，避免 Iris 复用过期 sampler。
        // 采样器沿用 Sampler0/1/2，兼容 Iris 对原版 sampler 名的校验。
        return createYuvRenderType(
                "bili_yuv420p_entity",
                videoSetup(YUV420P_ENTITY, YUV420P_OIT)
                        .withTexture("Sampler0", yTexture)
                        .withTexture("Sampler1", uTexture)
                        .withTexture("Sampler2", vTexture)
                        .createRenderSetup());
    }

    public static RenderType nv12Entity(Identifier yTexture, Identifier uvTexture, Identifier placeholderTexture) {
        return nv12Entity("bili_nv12_entity", yTexture, uvTexture, placeholderTexture);
    }

    public static RenderType padNv12Entity(Identifier yTexture, Identifier uvTexture, Identifier placeholderTexture) {
        return nv12Entity("ncpb_pad_video_nv12_entity", yTexture, uvTexture, placeholderTexture);
    }

    private static RenderType nv12Entity(String name, Identifier yTexture, Identifier uvTexture,
            Identifier placeholderTexture) {
        IrisShaderpackCompat.prepareYuvPipelineForCurrentShaderpackState(NV12_ENTITY,
                YUV420P_TEXTURED_PROBE_ENTITY);
        return createYuvRenderType(
                name,
                videoSetup(NV12_ENTITY, NV12_OIT)
                        .withTexture("Sampler0", yTexture)
                        .withTexture("Sampler1", uvTexture)
                        .withTexture("Sampler2", placeholderTexture)
                        .createRenderSetup());
    }

    private static RenderSetup.RenderSetupBuilder videoSetup(RenderPipeline pipeline, OitPipelineSet oit) {
        var builder = RenderSetup.builder(pipeline).setOitPipelines(oit);
        if (VideoYuvRenderPolicy.useSolidFeatureStage()) builder.withForcedSolidModelPhase();
        return builder;
    }

    private static RenderType createYuvRenderType(String name, RenderSetup setup) {
        return RenderType.create(name, setup);
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
        synchronized (RGBA_ENTITY_CACHE) {
            return RGBA_ENTITY_CACHE.computeIfAbsent(texture, key -> {
                LOGGER.debug("创建视频 RGBA Iris 兼容 RenderType: texture={}, pipeline=RGBA_FLAT_SOLID, samplers=Sampler0/1/2, lightmap=full",
                        key);
                return videoRgbaEntity("bili_video_rgba_entity", key);
            });
                }
    }

        /** Opaque/depth-writing RGBA surface with binary-alpha cutout. */
        public static RenderType videoRgbaCutoutEntity(Identifier texture) {
                synchronized (RGBA_CUTOUT_ENTITY_CACHE) {
                        return RGBA_CUTOUT_ENTITY_CACHE.computeIfAbsent(texture,
                                        key -> videoRgbaEntity("bili_video_rgba_cutout_entity",
                                                        RGBA_FLAT_CUTOUT, key));
                }
        }

        static RenderType videoRgbaEntity(Identifier texture, VideoRgbaRenderPass renderPass) {
                return switch (renderPass) {
                        case SOLID -> videoRgbaEntity(texture);
                        case CUTOUT -> videoRgbaCutoutEntity(texture);
                        case TRANSLUCENT -> videoRgbaTranslucentEntity(texture);
                        case SKIP -> throw new IllegalArgumentException("SKIP does not have a RenderType");
                };
        }

        /**
         * Shaderpack-safe translucent RGBA surface.  Iris may replace the vanilla entity
         * program with one that validates all three conventional sampler slots, so the
         * render setup must carry the same complete binding set as the YUV/RGBA video path.
         */
        public static RenderType videoRgbaTranslucentEntity(Identifier texture) {
                synchronized (RGBA_TRANSLUCENT_ENTITY_CACHE) {
                        return RGBA_TRANSLUCENT_ENTITY_CACHE.computeIfAbsent(texture,
                                        key -> videoRgbaEntity("bili_video_rgba_translucent_entity",
                                                        RGBA_FLAT_TRANSLUCENT, key));
                }
        }

        public static RenderType padVideoRgbaEntity(Identifier texture) {
                return videoRgbaEntity("ncpb_pad_video_rgba_entity", texture);
        }

        private static RenderType videoRgbaEntity(String name, Identifier texture) {
                return videoRgbaEntity(name, RGBA_FLAT_SOLID, texture);
        }

        private static RenderType videoRgbaEntity(String name, RenderPipeline pipeline, Identifier texture) {
                return RenderType.create(
                                name,
                                RenderSetup.builder(pipeline)
                                                .withTexture("Sampler0", texture)
                                                // Sampler1 保持 Iris 实体管线兼容；Sampler2 使用真实满亮 lightmap。
                                                .withTexture("Sampler1", texture)
                                                .useLightmap()
                                                .createRenderSetup());
        }

}
