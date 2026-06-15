package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可选 Iris 集成辅助工具。
 *
 * <p>
 * YUV/NV12 路径需要使用本模组自己的 fragment shader 和多平面采样器。启用 Iris
 * shaderpack 后，仅靠公开的 pipeline 分类并不够：shaderpack 仍可能替换最终使用的程序。
 * 因此渲染器为本模组自己的 YUV pipeline 保留了一个作用域很窄的旁路，并把 NV12/YUV420P
 * 视为正常路径。仍可通过 {@code -Dbili.video.iris.disable_yuv_shader=true} 启用 CPU RGBA 回退。
 * </p>
 */
public final class IrisShaderpackCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FORCE_YUV_SHADER_WITH_IRIS = "bili.video.iris.force_yuv_shader";
    private static final String DISABLE_CUSTOM_YUV_SHADER = "bili.video.iris.disable_yuv_shader";
    private static final String ALLOW_THREE_PLANE_IRIS_YUV = "bili.video.iris.allow_three_plane";
    private static final String ENABLE_YUV_SHADERPACK_BYPASS = "bili.video.iris.yuv_bypass";
    private static final String IRIS_YUV_PROGRAM = "bili.video.iris.program";
    private static final String IRIS_YUV_SHADER_KEY = "bili.video.iris.shader_key";
    private static final String DEFAULT_IRIS_YUV_PROGRAM = "ENTITIES_TRANSLUCENT";

    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile boolean lastShaderPackInUse;
    private static final Set<Identifier> ASSIGNED_YUV_PIPELINES = ConcurrentHashMap.newKeySet();
    private static final Set<Identifier> ASSIGNED_TEXTURED_PROBE_PIPELINES = ConcurrentHashMap.newKeySet();

    private IrisShaderpackCompat() {
    }

    static boolean isForceYuvShaderEnabled() {
        return Boolean.parseBoolean(System.getProperty(FORCE_YUV_SHADER_WITH_IRIS, "true"));
    }

    static String configuredYuvProgramName() {
        return System.getProperty(IRIS_YUV_PROGRAM, DEFAULT_IRIS_YUV_PROGRAM).trim().toUpperCase();
    }

    static String configuredYuvShaderKeyName() {
        return System.getProperty(IRIS_YUV_SHADER_KEY, "").trim().toUpperCase();
    }

    static boolean isTexturedProbeProgram() {
        return shouldApplyIrisYuvCompatibility() && "TEXTURED".equals(configuredYuvProgramName());
    }

    static boolean isThreePlaneIrisYuvAllowed() {
        return Boolean.parseBoolean(System.getProperty(ALLOW_THREE_PLANE_IRIS_YUV, "true"));
    }

    static boolean isYuvShaderpackBypassEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_YUV_SHADERPACK_BYPASS, "true"));
    }

    static boolean shouldApplyIrisYuvCompatibility() {
        return isForceYuvShaderEnabled() && isShaderPackInUse();
    }

    static boolean shouldUseSingleSamplerProbe() {
        return shouldApplyIrisYuvCompatibility() && !isThreePlaneIrisYuvAllowed();
    }

    static boolean shouldForceSafeProbeRenderType() {
        return shouldApplyIrisYuvCompatibility() && !isThreePlaneIrisYuvAllowed();
    }

    static boolean shouldRegisterThreePlaneYuvPipeline() {
        if (!isForceYuvShaderEnabled() || isThreePlaneIrisYuvAllowed()) {
            return true;
        }
        return !isShaderPackInUse();
    }

    static boolean shouldRegisterTexturedProbePipeline() {
        // 在强制 Iris 安全模式下，即使只是注册一个自定义探针 pipeline，也足以让 Iris 尝试把它
        // 匹配进 shaderpack 覆盖表。Complementary/Iris 1.10.9 可能会在我们提交视频四边形前，
        // 因 "Missing sampler Sampler1" 让世界主渲染 pass 崩溃。因此探针改用原版 pipeline。
        return !shouldForceSafeProbeRenderType();
    }

    static boolean shouldDisableCustomYuvShader() {
        if (Boolean.getBoolean(DISABLE_CUSTOM_YUV_SHADER)) {
            return true;
        }
        if (isForceYuvShaderEnabled()) {
            return false;
        }
        return isShaderPackInUse();
    }

    public static boolean isShaderPackInUse() {
        ensureInitialized();
        if (!available) {
            return false;
        }
        try {
            boolean inUse = IrisDirectCompat.isShaderPackInUse();
            if (inUse != lastShaderPackInUse) {
                lastShaderPackInUse = inUse;
                ASSIGNED_YUV_PIPELINES.clear();
                ASSIGNED_TEXTURED_PROBE_PIPELINES.clear();
                LOGGER.info("Iris shaderpack 状态变化: shaderpackInUse={}, customYuvShaderDisabled={}", inUse,
                        Boolean.getBoolean(DISABLE_CUSTOM_YUV_SHADER) || (!isForceYuvShaderEnabled() && inUse));
            }
            return inUse;
        } catch (RuntimeException e) {
            LOGGER.debug("Iris API 查询 shaderpack 状态失败，按未启用 shaderpack 处理", e);
            return false;
        }
    }

    static void assignYuvPipelineIfRequested(RenderPipeline pipeline) {
        if (!shouldApplyIrisYuvCompatibility() || !isThreePlaneIrisYuvAllowed()) {
            return;
        }
        assignPipelineIfRequested(pipeline, configuredYuvProgramName(), false);
    }

    static void assignTexturedProbePipelineIfRequested(RenderPipeline pipeline) {
        if (!shouldApplyIrisYuvCompatibility() || isThreePlaneIrisYuvAllowed()) {
            return;
        }
        assignPipelineIfRequested(pipeline, "TEXTURED", true);
    }

    static void prepareYuvPipelineForCurrentShaderpackState(RenderPipeline yuvPipeline,
            RenderPipeline texturedProbePipeline) {
        if (!shouldApplyIrisYuvCompatibility()) {
            return;
        }
        if (isThreePlaneIrisYuvAllowed()) {
            assignPipelineIfRequested(yuvPipeline, configuredYuvProgramName(), false);
        } else {
            assignPipelineIfRequested(texturedProbePipeline, "TEXTURED", true);
        }
    }

    private static void assignPipelineIfRequested(RenderPipeline pipeline, String programName, boolean texturedProbe) {
        if (!isForceYuvShaderEnabled() || pipeline == null) {
            return;
        }
        Identifier pipelineId = pipeline.getLocation();
        Set<Identifier> assignedPipelines = texturedProbe ? ASSIGNED_TEXTURED_PROBE_PIPELINES : ASSIGNED_YUV_PIPELINES;
        ensureInitialized();
        if (!available) {
            LOGGER.warn("已强制启用 Iris shaderpack 下的 YUV shader，但 Iris assignPipeline API 不可用；将直接尝试原始 pipeline");
            return;
        }
        if (assignedPipelines.contains(pipelineId)) {
            return;
        }
        synchronized (IrisShaderpackCompat.class) {
            if (assignedPipelines.contains(pipelineId)) {
                return;
            }
            try {
                String shaderKeyName = configuredYuvShaderKeyName();
                if (!shaderKeyName.isBlank() && !texturedProbe) {
                    IrisDirectCompat.assignYuvPipelineToShaderKey(pipeline, shaderKeyName);
                } else {
                    IrisDirectCompat.assignYuvPipeline(pipeline, programName);
                }
                assignedPipelines.add(pipelineId);
                LOGGER.info("已将视频 {} pipeline 分配给 {}。可用 -D{} 或 -D{} 覆盖 Iris 映射；多平面 YUV 开关 -D{}",
                        texturedProbe ? "TEXTURED 单采样可见性探针" : "YUV",
                        !configuredYuvShaderKeyName().isBlank() && !texturedProbe
                                ? "ShaderKey." + configuredYuvShaderKeyName()
                                : "IrisProgram." + programName,
                        IRIS_YUV_PROGRAM, IRIS_YUV_SHADER_KEY, ALLOW_THREE_PLANE_IRIS_YUV);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("未知 Iris 映射值。IrisProgram='{}', ShaderKey='{}'，将回退为 IrisProgram.{}。"
                        + "常用 ShaderKey 包括 BASIC、TEXTURED、ENTITIES_TRANSLUCENT、PARTICLES、PARTICLES_TRANS 等",
                        System.getProperty(IRIS_YUV_PROGRAM), System.getProperty(IRIS_YUV_SHADER_KEY),
                        DEFAULT_IRIS_YUV_PROGRAM, e);
                IrisDirectCompat.assignYuvPipeline(pipeline, DEFAULT_IRIS_YUV_PROGRAM);
                assignedPipelines.add(pipelineId);
            } catch (RuntimeException e) {
                LOGGER.warn("Iris assignPipeline(YUV, {}) 失败，将直接尝试原始 YUV pipeline",
                        programName, e);
            }
        }
    }

    private static void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (IrisShaderpackCompat.class) {
            if (initialized) {
                return;
            }
            initialized = true;
            try {
                if (!ModList.get().isLoaded("iris")) {
                    available = false;
                    return;
                }
                IrisDirectCompat.isShaderPackInUse();
                available = true;
                LOGGER.info("检测到 Iris API，启用 shaderpack 兼容检测");
            } catch (LinkageError e) {
                available = false;
            } catch (RuntimeException e) {
                available = false;
                LOGGER.debug("Iris API 初始化失败，按未安装 Iris 处理", e);
            }
        }
    }
}
