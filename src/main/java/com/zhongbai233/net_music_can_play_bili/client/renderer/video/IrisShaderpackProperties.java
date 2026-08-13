package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

import java.util.Locale;

/** Dynamic JVM property boundary for Iris shaderpack YUV compatibility. */
final class IrisShaderpackProperties {
    static final String FORCE_YUV_SHADER = "ncpb.video.iris.force_yuv_shader";
    static final String DISABLE_CUSTOM_YUV_SHADER = "ncpb.video.iris.disable_yuv_shader";
    static final String LEGACY_DISABLE_CUSTOM_YUV_SHADER = "bili.video.iris.disable_yuv_shader";
    static final String ALLOW_THREE_PLANE_YUV = "ncpb.video.iris.allow_three_plane";
    static final String ENABLE_YUV_SHADERPACK_BYPASS = "ncpb.video.iris.yuv_bypass";
    static final String YUV_PROGRAM = "ncpb.video.iris.program";
    static final String YUV_SHADER_KEY = "ncpb.video.iris.shader_key";
    static final String DEFAULT_YUV_PROGRAM = "ENTITIES_TRANSLUCENT";

    private IrisShaderpackProperties() {
    }

    static boolean forceYuvShaderEnabled() {
        return NcpbSystemProperties.booleanValue(FORCE_YUV_SHADER, true);
    }

    static boolean customYuvShaderDisabled() {
        return NcpbSystemProperties.booleanValue(
                DISABLE_CUSTOM_YUV_SHADER, LEGACY_DISABLE_CUSTOM_YUV_SHADER, false);
    }

    static boolean threePlaneYuvAllowed() {
        return NcpbSystemProperties.booleanValue(ALLOW_THREE_PLANE_YUV, true);
    }

    static boolean yuvShaderpackBypassEnabled() {
        return NcpbSystemProperties.booleanValue(ENABLE_YUV_SHADERPACK_BYPASS, true);
    }

    static String yuvProgramName() {
        return NcpbSystemProperties.stringValue(YUV_PROGRAM, DEFAULT_YUV_PROGRAM)
                .toUpperCase(Locale.ROOT);
    }

    static String yuvShaderKeyName() {
        return NcpbSystemProperties.stringValue(YUV_SHADER_KEY, "").toUpperCase(Locale.ROOT);
    }
}
